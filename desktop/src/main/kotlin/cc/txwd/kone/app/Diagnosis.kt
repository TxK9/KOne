package cc.txwd.kone.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** How a check went. */
enum class Outcome { Running, Pass, Warn, Fail }

/** One check of a diagnosis: what was checked, how it went, and a few words on it. */
data class Check(val title: String, val outcome: Outcome, val detail: String = "")

/**
 * The checks of the network a diagnosis makes, through the same path as the person's traffic:
 * KOne's tunnel when connected with the adapter, KOne's local port when connected as the system
 * proxy ([viaPort]), or the network as it is otherwise.
 */
object Diagnosis {
    /** Sites everyone knows, at home and abroad; each is asked for its front page, nothing more. */
    val SITES = listOf("Google" to "https://www.gstatic.com/generate_204", "GitHub" to "https://github.com", "YouTube" to "https://www.youtube.com", "Baidu" to "https://www.baidu.com")

    /** A name at home and one abroad. */
    val NAMES = listOf("www.qq.com", "www.google.com")

    /** Whether each name in [names] resolves within five seconds. */
    suspend fun names(names: List<String> = NAMES): Check = coroutineScope {
        val answered = names.map { name ->
            async(Dispatchers.IO) { withTimeoutOrNull(5_000) { runCatching { InetAddress.getAllByName(name).isNotEmpty() }.getOrDefault(false) } == true }
        }.awaitAll()
        val count = answered.count { it }
        when (count) {
            names.size -> Check("DNS", Outcome.Pass, "Names resolve at home and abroad")
            0 -> Check("DNS", Outcome.Fail, "No name resolves")
            else -> Check("DNS", Outcome.Warn, "Not resolved: " + names.filterIndexed { i, _ -> !answered[i] }.joinToString())
        }
    }

    /** How long each site takes to answer, or that it did not. */
    suspend fun sites(viaPort: Int?, sites: List<Pair<String, String>> = SITES): List<Check> = coroutineScope {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER)
            .apply { if (viaPort != null) proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", viaPort))) }.build()
        sites.map { (name, url) ->
            async(Dispatchers.IO) {
                val started = System.nanoTime()
                val answered = runCatching {
                    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 100..599
                }.getOrDefault(false)
                val millis = (System.nanoTime() - started) / 1_000_000
                when {
                    !answered -> Check(name, Outcome.Fail, "No answer")
                    millis >= 3_000 -> Check(name, Outcome.Warn, "$millis ms")
                    else -> Check(name, Outcome.Pass, "$millis ms")
                }
            }
        }.awaitAll()
    }

    /** One plain suggestion for the first check that failed, or null when nothing did. */
    fun advice(checks: List<Check>): String? {
        val failed = checks.firstOrNull { it.outcome == Outcome.Fail } ?: return null
        val sites = checks.filter { it.title in SITES.map { site -> site.first } }
        return when {
            failed.title == "Key" -> "Enter a key, or wait for the console to send nodes"
            failed.title == "Service" -> "Reinstall KOne, or choose System proxy in Tools"
            failed.title == "Node" -> "Try another node, or Test all on Nodes"
            failed.title == "DNS" -> "Try another DNS server in Settings"
            sites.isNotEmpty() && sites.all { it.outcome == Outcome.Fail } -> "Nothing answers: check the node and the mode in Tools"
            failed.title in SITES.map { it.first } -> "Some sites do not answer: check your Rules"
            else -> null
        }
    }

    /** A delay in milliseconds as a check of [name]. */
    fun node(name: String, delay: Int?): Check = when {
        delay == null -> Check("Node", Outcome.Fail, "$name did not answer")
        delay >= 1_000 -> Check("Node", Outcome.Warn, "$name $delay ms")
        else -> Check("Node", Outcome.Pass, "$name $delay ms")
    }
}
