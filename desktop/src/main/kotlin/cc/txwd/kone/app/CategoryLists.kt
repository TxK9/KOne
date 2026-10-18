package cc.txwd.kone.app

import cc.txwd.kone.core.Categories
import cc.txwd.kone.core.UserRules
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

/** What a download gave: the list unchanged since [etag], or new bytes. */
sealed interface ListDownload {
    data object Same : ListDownload
    class Got(val bytes: ByteArray, val etag: String?) : ListDownload
}

/** How one category list stands: kept (and when it was last fetched or found unchanged), and the last failure, if the last try failed. */
data class ListStatus(val kept: Boolean, val checked: Long?, val error: String?)

/**
 * The category lists the person's rules name, kept by the App in rule-sets\ in its folder:
 * downloaded over HTTPS from SagerNet's fixed address, checked (size, the binary rule-set header,
 * and sing-box reading it, as [parses] does), and only then put in place of the copy before.
 * A failed download or a list that fails a check leaves the last good copy as it was.
 *
 * Each list is looked at again once a day, asking only whether it changed (ETag); one that
 * failed is tried again after an hour. The service checks each list again before it keeps it.
 */
class CategoryLists(
    private val folder: File,
    private val fetch: (url: String, etag: String?) -> ListDownload,
    private val parses: (File) -> Boolean,
) {
    private val index = File(folder, "lists.properties")
    private val values = Properties().apply { runCatching { index.inputStream().use(::load) } }

    /** The SHA-256 of the list kept as [name], or null when none is kept intact. */
    @Synchronized
    fun digest(name: String): String? {
        val sha = values.getProperty("$name.sha") ?: return null
        val file = file(name)
        if (!file.isFile || file.length() > MOST_BYTES) return null
        return sha.takeIf { sha256(file.readBytes()) == it }
    }

    @Synchronized
    fun bytes(name: String): ByteArray? = digest(name)?.let { file(name).readBytes() }

    @Synchronized
    fun status(name: String) = ListStatus(digest(name) != null, values.getProperty("$name.checked")?.toLongOrNull(), values.getProperty("$name.error"))

    /** Whether [name] should be looked at now: never kept, a day since it was, or an hour since a failure. */
    @Synchronized
    fun due(name: String, now: Long): Boolean {
        val failed = values.getProperty("$name.failed")?.toLongOrNull()
        if (failed != null && now - failed < RETRY) return false
        val checked = values.getProperty("$name.checked")?.toLongOrNull() ?: return true
        return digest(name) == null || now - checked >= DAY
    }

    /**
     * Downloads [name] (asking only whether it changed, when one is kept), checks it and keeps it.
     * True when a new copy was put in place; the failure is kept for the page otherwise.
     */
    fun update(name: String, now: Long): Boolean {
        if (!UserRules.isSetName(name)) return false
        val etag = synchronized(this) { values.getProperty("$name.etag")?.takeIf { digest(name) != null } }
        val fetched = runCatching { fetch(Categories.url(name), etag) }.getOrElse { failure ->
            fail(name, now, (failure as? IOException)?.message ?: "Download failed")
            return false
        }
        if (fetched is ListDownload.Same) {
            synchronized(this) {
                values.setProperty("$name.checked", "$now")
                values.remove("$name.error"); values.remove("$name.failed")
                save()
            }
            return false
        }
        val bytes = (fetched as ListDownload.Got).bytes
        if (!looksLikeAList(bytes)) {
            fail(name, now, "Not a rule set")
            return false
        }
        synchronized(this) {
            folder.mkdirs()
            val fresh = File(folder, "$name.srs.new")
            fresh.writeBytes(bytes)
            if (!parses(fresh)) {
                fresh.delete()
                fail(name, now, "sing-box cannot read this list")
                return false
            }
            val target = file(name)
            if (!fresh.renameTo(target) && !(target.delete() && fresh.renameTo(target))) {
                fresh.delete()
                fail(name, now, "Could not save the list")
                return false
            }
            values.setProperty("$name.sha", sha256(bytes))
            values.setProperty("$name.checked", "$now")
            val newTag = fetched.etag
            if (newTag != null) values.setProperty("$name.etag", newTag) else values.remove("$name.etag")
            values.remove("$name.error"); values.remove("$name.failed")
            save()
        }
        return true
    }

    /** Lets go of the lists no rule names any more. */
    @Synchronized
    fun keepOnly(names: Set<String>) {
        val known = values.stringPropertyNames().map { it.substringBeforeLast('.') }.toSet()
        val gone = known - names
        if (gone.isEmpty()) return
        gone.forEach { name ->
            listOf("sha", "checked", "etag", "error", "failed").forEach { values.remove("$name.$it") }
            if (UserRules.isSetName(name)) file(name).delete()
        }
        save()
    }

    private fun file(name: String) = File(folder, "$name.srs")

    @Synchronized
    private fun fail(name: String, now: Long, why: String) {
        values.setProperty("$name.error", why)
        values.setProperty("$name.failed", "$now")
        save()
    }

    private fun save() {
        runCatching {
            folder.mkdirs()
            index.outputStream().use { values.store(it, null) }
        }
    }

    companion object {
        /** One list at most, as the service takes it (desktop/service/sets.go). */
        const val MOST_BYTES = 4 shl 20
        const val DAY = 24 * 60 * 60 * 1000L
        const val RETRY = 60 * 60 * 1000L

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The size and header of a sing-box binary rule set ("SRS", format 1 to 5), as the service checks them. */
        fun looksLikeAList(bytes: ByteArray) =
            bytes.size in 5..MOST_BYTES && bytes[0] == 'S'.code.toByte() && bytes[1] == 'R'.code.toByte() && bytes[2] == 'S'.code.toByte() && bytes[3].toInt() in 1..5

        /**
         * Downloads over HTTPS only, through KOne's local port when [viaPort] is given, reading at
         * most [MOST_BYTES]: an answer larger than that is refused, not cut short.
         */
        fun download(url: String, etag: String?, viaPort: Int?): ListDownload {
            require(url.startsWith("https://")) { "HTTPS only" }
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(if (viaPort != null) ProxySelector.of(InetSocketAddress("127.0.0.1", viaPort)) else HttpClient.Builder.NO_PROXY).build()
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "KOne").GET()
                .apply { if (etag != null) header("If-None-Match", etag) }.build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (failure: IOException) {
                throw IOException("Could not reach the list's server")
            }
            response.body().use { body ->
                if (response.statusCode() == 304) return ListDownload.Same
                if (response.statusCode() == 404) throw IOException("No such list")
                if (response.statusCode() != 200 || !response.uri().scheme.equals("https", ignoreCase = true)) throw IOException("The server answered ${response.statusCode()}")
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MOST_BYTES) throw IOException("The list is too large")
                }
                return ListDownload.Got(out.toByteArray(), response.headers().firstValue("ETag").orElse(null))
            }
        }

        /** Whether the sing-box at [core] reads [file] as the template will use it. */
        fun singBoxReads(core: File, file: File): Boolean = runCatching {
            val config = """{"route":{"rule_set":[{"type":"local","tag":"check","format":"binary","path":${kotlinx.serialization.json.JsonPrimitive(file.absolutePath.replace('\\', '/'))}}]}}"""
            val process = ProcessBuilder(core.path, "check", "-c", "stdin", "--disable-color").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.use { it.write(config.toByteArray()) }
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}
