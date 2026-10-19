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
/** How one check went: the shared core's ([cc.txwd.kone.shared.Outcome]). */
typealias Outcome = cc.txwd.kone.shared.Outcome

/** One check of a diagnosis: the shared core's ([cc.txwd.kone.shared.Check]). */
typealias Check = cc.txwd.kone.shared.Check

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

    /** Outside China: the shared core's sites and names. */
    val GLOBAL_SITES = cc.txwd.kone.shared.Region.checkSites(cc.txwd.kone.shared.Region.GLOBAL)

    /** The sites and names diagnosis tries in [region] ([cc.txwd.kone.shared.Region]). */
    fun sitesFor(region: String) = cc.txwd.kone.shared.Region.checkSites(region)
    fun namesFor(region: String) = cc.txwd.kone.shared.Region.checkNames(region)

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
        // The network, the key and the service come first; after them, other VPNs or proxies are the likeliest reason anything else fails.
        val basic = checks.firstOrNull { it.outcome == Outcome.Fail && it.title in setOf("Network", CLOCK, "Key", "Service") }
        val others = checks.firstOrNull { it.title == Conflicts.TITLE && it.outcome == Outcome.Warn }
        if (basic == null && others != null) return "Turn off ${others.detail} while KOne is on: they can take traffic before it"
        val failed = basic ?: checks.firstOrNull { it.outcome == Outcome.Fail } ?: return null
        val sites = checks.filter { it.title in (SITES + GLOBAL_SITES).map { site -> site.first } }
        return when {
            failed.title == "Network" && failed.detail == SIGN_IN -> "Sign in to this network: Sign in on the dashboard opens its page"
            failed.title == "Network" -> "This computer is offline: check Wi-Fi or the cable"
            failed.title == CLOCK -> "Windows' clock is ${failed.detail}: turn on Set time automatically (Settings → Time & language), then connect again"
            failed.title == "Key" -> "Enter a key, or wait for the console to send nodes"
            failed.title == "Service" -> "Reinstall KOne, or choose System proxy in Tools"
            failed.title == "Node" -> "Try another node, or Test all on Nodes"
            failed.title == "DNS" -> "Try another DNS server in Settings"
            sites.isNotEmpty() && sites.all { it.outcome == Outcome.Fail } -> "Nothing answers: check the node and the mode in Tools"
            failed.title in (SITES + GLOBAL_SITES).map { it.first } -> "Some sites do not answer: check your Rules"
            else -> null
        }
    }

    /** A diagnosis as plain text for whoever helps: see [cc.txwd.kone.shared.Checks.report]. */
    fun report(checks: List<Check>, advice: String?, context: List<Pair<String, String>>): String =
        cc.txwd.kone.shared.Checks.report(checks, advice, context)

    /** The network itself, before any node: open, holding traffic for a sign-in page, or offline. */
    fun network(check: cc.txwd.kone.core.ReachCheck): Check = when (check.reach) {
        cc.txwd.kone.core.Reach.Open -> Check("Network", Outcome.Pass, "Open")
        cc.txwd.kone.core.Reach.SignIn -> Check("Network", Outcome.Fail, SIGN_IN)
        cc.txwd.kone.core.Reach.Offline -> Check("Network", Outcome.Fail, "Nothing answers")
    }

    const val SIGN_IN = "Wants you to sign in"

    /**
     * This computer's clock against a server's: VMess turns away a client more than 90 seconds
     * off, and certificates fail when the clock is far out, so a node "does not work" for a reason
     * no node can fix. Null when unknown.
     */
    fun clock(skew: Long?): Check? = cc.txwd.kone.shared.Checks.clock(skew)

    const val CLOCK = cc.txwd.kone.shared.Checks.CLOCK

    /** A delay in milliseconds as a check of [name]. */
    fun node(name: String, delay: Int?): Check = when {
        delay == null -> Check("Node", Outcome.Fail, "$name did not answer")
        delay >= 1_000 -> Check("Node", Outcome.Warn, "$name $delay ms")
        else -> Check("Node", Outcome.Pass, "$name $delay ms")
    }
}
