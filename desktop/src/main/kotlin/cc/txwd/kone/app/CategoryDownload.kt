package cc.txwd.kone.app

import cc.txwd.kone.shared.CategoryLists
import cc.txwd.kone.shared.ListDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** How the Windows App fetches and checks a category list for [CategoryLists]: Java's HTTP client, and its own sing-box. */
object CategoryDownload {
    private const val MOST_BYTES = CategoryLists.MOST_BYTES

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
