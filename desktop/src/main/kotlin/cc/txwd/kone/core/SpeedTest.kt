package cc.txwd.kone.core

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** How fast a node carried data each way, in megabits a second; null for a direction that failed. */
data class Speed(val down: Double?, val up: Double?)

/**
 * A bandwidth test through one node: KOne starts a core of its own for it (the live connection is
 * left alone), with a local port, and downloads and uploads through that port for a few seconds
 * each, from and to Cloudflare's speed test. At most [downBytes] down and [upBytes] up.
 */
class SpeedTest(
    private val downUrl: String = "https://speed.cloudflare.com/__down?bytes=$DOWN_BYTES",
    private val upUrl: String = "https://speed.cloudflare.com/__up",
    private val seconds: Long = 10,
    private val upBytes: Int = UP_BYTES,
) {
    /** Measures through the local proxy on [port]. Blocks for up to twice [seconds]. */
    fun run(port: Int): Speed {
        val client = HttpClient.newBuilder().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(Duration.ofSeconds(8)).build()
        return Speed(download(client), upload(client))
    }

    private fun download(client: HttpClient): Double? = runCatching {
        val request = HttpRequest.newBuilder(URI(downUrl)).timeout(Duration.ofSeconds(seconds + 8)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            if (response.statusCode() != 200) return null
            measure(stream)
        }
    }.getOrNull()

    /** Bytes read until the body ends or the time is up; the clock starts at the first byte, so the handshake is not counted. */
    private fun measure(stream: InputStream): Double? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var started = 0L
        val deadline = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (started == 0L) started = System.nanoTime() else total += read
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        return if (started == 0L || elapsed <= 0.01 || total == 0L) null else total * 8 / elapsed / 1e6
    }

    private fun upload(client: HttpClient): Double? = runCatching {
        val body = ByteArray(upBytes)
        val request = HttpRequest.newBuilder(URI(upUrl)).timeout(Duration.ofSeconds(seconds + 8))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        val started = System.nanoTime()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        val elapsed = (System.nanoTime() - started) / 1e9
        if (response.statusCode() !in 200..299 || elapsed <= 0.01) null else body.size * 8 / elapsed / 1e6
    }.getOrNull()

    companion object {
        const val DOWN_BYTES = 25_000_000
        const val UP_BYTES = 10_000_000
    }
}
