package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import javax.net.ssl.SSLContext

/** A newer KOne, as the publisher-signed latest.json names it. */
data class Release(val version: String, val msi: URI, val size: Long, val sha256: String)

class UpdateException(message: String) : IOException(message)

/**
 * Updates from the release website. latest.json names the newest MSI with its size and SHA-256;
 * latest.json.sig beside it is the Ed25519 signature of those exact bytes by the release-signing
 * key, whose public half is built into KOne. A manifest that is unsigned, signed by another key,
 * not for KOne, not newer, or that points anywhere but HTTPS is not taken, and the MSI must match
 * it to the byte before Windows Installer is asked to run it.
 */
class Updater(
    private val manifest: URI,
    private val key: PublicKey,
    private val current: String,
    private val folder: File,
    tls: SSLContext? = null,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply { tls?.let(::sslContext) }
        .build()

    init {
        require(manifest.scheme == "https") { "updates come over HTTPS only" }
    }

    /** The newer release, or null when this KOne is the newest. */
    fun check(): Release? {
        val body = fetch(manifest, MANIFEST_LIMIT)
        val signature = fetch(manifest.resolve(manifest.path.substringAfterLast('/') + ".sig"), 1024)
        val release = Manifest.read(body, signature.decodeToString(), key) ?: throw UpdateException(tr("Invalid signature"))
        return release.takeIf { newer(it.version, current) }
    }

    /** Downloads [release] into the updates folder and checks it; the MSI, ready to install. */
    fun download(release: Release, progress: (Float) -> Unit = {}): File {
        folder.mkdirs()
        folder.listFiles()?.forEach { if (it.name != "KOne-${release.version}.msi") it.delete() }
        val target = File(folder, "KOne-${release.version}.msi")
        if (target.isFile && target.length() == release.size && sha256(target) == release.sha256) return target
        val partial = File(folder, target.name + ".part")
        val response = http.send(HttpRequest.newBuilder(release.msi).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200 || response.uri().scheme != "https") {
            response.body().close()
            throw UpdateException(tr("Download failed"))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            response.body().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 shl 10)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > release.size) throw UpdateException(tr("Invalid update package"))
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        progress(total.toFloat() / release.size)
                    }
                }
            }
            if (total != release.size || HexFormat.of().formatHex(digest.digest()) != release.sha256) throw UpdateException(tr("Invalid update package"))
        } catch (failure: IOException) {
            // Nothing that did not check out stays behind to be installed.
            partial.delete()
            throw failure
        }
        target.delete()
        if (!partial.renameTo(target)) throw UpdateException(tr("Download failed"))
        return target
    }

    private fun fetch(uri: URI, limit: Int): ByteArray {
        val response = runCatching {
            http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        }.getOrElse { throw UpdateException(tr("Unable to connect")) }
        response.body().use { input ->
            if (response.statusCode() != 200 || response.uri().scheme != "https") throw UpdateException(tr("Unable to connect"))
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) throw UpdateException(tr("Invalid signature"))
            return bytes
        }
    }

    companion object {
        private const val MANIFEST_LIMIT = 16 shl 10

        /** The updater this build was made with, or null when it was made without an update address and key. */
        fun configured(): Updater? {
            val address = Places.updates ?: return null
            val key = Places.updateKey?.let { runCatching { Manifest.publicKey(it) }.getOrNull() } ?: return null
            return runCatching { Updater(URI(address), key, Places.version, File(Places.data, "updates")) }.getOrNull()
        }

        /** Whether [version] comes after [than]: 1.0.10 after 1.0.9. */
        fun newer(version: String, than: String): Boolean {
            val a = version.split('.').map { it.toIntOrNull() ?: return false }
            val b = than.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /**
         * Hands [msi] to Windows Installer and starts KOne again once it is done (the new KOne, or
         * the old one if the person said no to Windows). KOne quits right after, so nothing of it
         * is in use while the files are replaced.
         */
        fun install(msi: File, launcher: File) {
            fun quoted(text: String) = "'" + text.replace("'", "''") + "'"
            val script = """
                ${'$'}msi = ${quoted(msi.path)}
                Start-Process -FilePath msiexec.exe -ArgumentList @('/i', ('"' + ${'$'}msi + '"'), '/passive', '/norestart') -Wait
                Start-Process -FilePath ${quoted(launcher.path)}
            """.trimIndent()
            val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded).start()
        }
    }
}

/** latest.json and its signature: written by the release step, read by every KOne. */
object Manifest {
    /** latest.json for [msi], to be served from [url] (the MSI's own address on the website). */
    fun write(version: String, url: String, msi: File): ByteArray {
        return buildJsonObject {
            put("app", "KOne")
            put("version", version)
            put("msi", url)
            put("size", msi.length())
            put("sha256", sha256(msi))
        }.toString().toByteArray()
    }

    fun sign(body: ByteArray, key: PrivateKey): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").apply { initSign(key); update(body) }.sign())

    /** The release [body] names, when [signature] is the update key's over exactly these bytes; null otherwise. */
    fun read(body: ByteArray, signature: String, key: PublicKey): Release? {
        val signed = runCatching {
            Signature.getInstance("Ed25519").apply { initVerify(key); update(body) }.verify(Base64.getDecoder().decode(signature.trim()))
        }.getOrDefault(false)
        if (!signed) return null
        val json = runCatching { Json.parseToJsonElement(body.decodeToString()) as JsonObject }.getOrNull() ?: return null
        fun text(name: String) = (json[name] as? JsonPrimitive)?.contentOrNull
        if (text("app") != "KOne") return null
        val version = text("version")?.takeIf { it.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,5}""")) } ?: return null
        val msi = text("msi")?.let { runCatching { URI(it) }.getOrNull() }?.takeIf { it.scheme == "https" && it.host != null } ?: return null
        val size = (json["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..(1L shl 30) } ?: return null
        val sha256 = text("sha256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        return Release(version, msi, size, sha256)
    }

    fun publicKey(base64: String): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))

    fun privateKey(base64: String): PrivateKey =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 shl 10)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
