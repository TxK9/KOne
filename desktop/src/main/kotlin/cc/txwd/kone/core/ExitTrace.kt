package cc.txwd.kone.core

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The address a node's exit presents, read the way the phone reads it (ExitIpDetector). */
object ExitTrace {
    private val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    /**
     * Through the tunnel once it is up: Cloudflare's trace gives the exit address in one short
     * reply. Connected as the system proxy, the App's own requests do not follow Windows' proxy,
     * so they go through KOne's local port ([viaPort]) instead.
     */
    fun detect(viaPort: Int? = null): String? = runCatching {
        val client = if (viaPort == null) client else HttpClient.newBuilder()
            .proxy(java.net.ProxySelector.of(java.net.InetSocketAddress("127.0.0.1", viaPort))).connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        val request = HttpRequest.newBuilder(URI("https://www.cloudflare.com/cdn-cgi/trace")).timeout(Duration.ofSeconds(4))
            .header("Accept", "text/plain").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        parse(response.body().take(2048))
    }.getOrNull()

    /** Reads "ip=" from a trace; anything that is not a public address has no display value. */
    fun parse(trace: String): String? {
        val value = trace.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim() ?: return null
        if (value.isEmpty() || value.length > 45) return null
        if (':' in value) {
            if (value.any { it !in "0123456789abcdefABCDEF:." }) return null
        } else {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it.startsWith('0')) || it.toInt() > 255 }) return null
        }
        // The characters are checked above, so this never looks a name up.
        val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return null
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return null
        return value
    }
}
