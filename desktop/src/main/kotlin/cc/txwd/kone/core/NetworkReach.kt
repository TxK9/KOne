package cc.txwd.kone.core

import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** What the network the computer is on lets through, before any node: everything, nothing until someone signs in, or nothing at all. */
enum class Reach { Open, SignIn, Offline }

/** A [Reach], and for [Reach.SignIn] the page the network sent people to, when it said. */
data class ReachCheck(val reach: Reach, val page: String? = null)

/**
 * Whether the network itself is open, asked of the network rather than through a node: plain
 * HTTP to two well-known addresses, by IP so no name has to resolve (networks that hold people
 * at a sign-in page often answer no DNS but their own). Either one answering as it always does
 * means the network is open; an answer that is something else (a redirect, a login page) means
 * the network is holding traffic until someone signs in; no answer at all, that it is offline.
 *
 * While KOne's adapter is up the check goes through a core of KOne's own on [viaPort], which the
 * template sends straight out (sing-box.exe), so it sees the network as it is, not the tunnel.
 */
object NetworkReach {
    /**
     * An address to ask, and how it answers on an open network. Sending the asker on to the same
     * address over HTTPS is an open network's answer too: a sign-in page cannot hold a valid
     * certificate for the address, so it never sends people there.
     */
    class Target(val url: String, val answers: (status: Int, body: String) -> Boolean) {
        fun usual(status: Int, body: String, location: String?): Boolean =
            answers(status, body) || (status in 300..399 && location != null && location == "https://" + url.removePrefix("http://"))
    }

    val TARGETS = listOf(
        // Cloudflare's trace page names the host it was asked for.
        Target("http://1.1.1.1/cdn-cgi/trace") { status, body -> status == 200 && "fl=" in body && "h=1.1.1.1" in body },
        // AliDNS's JSON lookup, reachable where Cloudflare may not be.
        Target("http://223.5.5.5/resolve?name=www.example.com&type=1") { status, body -> status == 200 && body.trimStart().startsWith("{") && "\"Status\"" in body },
    )

    /** What one address gave: its usual answer, another answer (and where it pointed), or none. */
    sealed interface Heard {
        data object Usual : Heard
        data class Other(val location: String?) : Heard
        data object Nothing : Heard
    }

    fun check(viaPort: Int? = null, targets: List<Target> = TARGETS, timeoutMillis: Long = 5_000): ReachCheck {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(if (viaPort != null) ProxySelector.of(InetSocketAddress("127.0.0.1", viaPort)) else HttpClient.Builder.NO_PROXY).build()
        val asked = targets.map { target ->
            val request = HttpRequest.newBuilder(URI(target.url)).timeout(Duration.ofMillis(timeoutMillis)).header("Cache-Control", "no-cache").GET().build()
            client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply { response ->
                val body = response.body().use { stream -> String(stream.readNBytes(16 * 1024), Charsets.UTF_8) }
                val location = response.headers().firstValue("Location").orElse(null)
                if (target.usual(response.statusCode(), body, location)) Heard.Usual
                else Heard.Other(location)
            }.exceptionally { Heard.Nothing }
        }
        // The first usual answer settles it; otherwise every address has its time.
        val heard = mutableListOf<Heard>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis * 2)
        for (future in asked) {
            val left = (deadline - System.nanoTime()).coerceAtLeast(0)
            val one = runCatching { future.get(left, TimeUnit.NANOSECONDS) }.getOrDefault(Heard.Nothing)
            if (one == Heard.Usual) {
                asked.forEach { it.cancel(true) }
                return ReachCheck(Reach.Open)
            }
            heard += one
        }
        return verdict(heard)
    }

    /** What the answers add up to; a sign-in page is only one the network named with http or https. */
    fun verdict(heard: List<Heard>): ReachCheck {
        if (heard.any { it == Heard.Usual }) return ReachCheck(Reach.Open)
        val other = heard.filterIsInstance<Heard.Other>()
        if (other.isEmpty()) return ReachCheck(Reach.Offline)
        val page = other.mapNotNull { it.location }.firstOrNull { location ->
            runCatching { URI(location) }.getOrNull()?.let { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() } == true
        }
        return ReachCheck(Reach.SignIn, page)
    }

    /** A page to open to sign in when the network named none: any plain-HTTP page is sent to the sign-in page. */
    const val ANY_PAGE = "http://neverssl.com/"
}
