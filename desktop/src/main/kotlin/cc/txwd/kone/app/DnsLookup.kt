package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr


import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.DnsReply
import cc.txwd.kone.core.DnsWire
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.UserRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * A name looked up several ways at once, so a person can see where an answer goes wrong: as
 * programs on this computer see it, through KOne's own DNS with its rules, from the direct server
 * and from the remote one. Each way is one [Check]; [verdict] says what they add up to.
 */
object DnsLookup {
    /** A name as a person might paste it (a link, with a port or a trailing dot), or null when it is not a domain name. */
    fun clean(input: String): String? {
        var text = input.trim().lowercase()
        text = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (!text.startsWith('[')) text = text.substringBefore(':')
        text = text.trimEnd('.')
        return text.takeIf { it.length <= 253 && KoneConfig.isSite(it) }
    }

    /** Whether the console's refusals (the Claude block in [source]) name [name]; these are refused before any server is asked. */
    fun refused(name: String, source: JsonObject?): Boolean {
        source ?: return false
        return KoneConfig.refusals(source).any { rule ->
            fun names(field: String) = (rule[field] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
            names("domain").any { it == name } || names("domain_suffix").any { suffix ->
                if (suffix.startsWith('.')) name.endsWith(suffix) else name == suffix || name.endsWith(".$suffix")
            }
        }
    }

    /** As programs on this computer see it (Windows' resolver; KOne's own while the adapter is on). */
    fun system(name: String): Check = timed(tr("Apps")) {
        val found = InetAddress.getAllByName(name).map { if (it is Inet6Address) "AAAA " + UserRules.compressed(it.address) else "A ${it.hostAddress}" }
        DnsReply(0, found)
    }

    /** Through KOne's DNS, with its rules: IPv4, and IPv6 when it is allowed. */
    fun core(api: ClashApi, name: String, ipv6: Boolean): Check = timed("KOne") {
        val four = api.dnsQuery(name, "A") ?: throw java.io.IOException(tr("KOne's DNS did not answer"))
        val six = if (ipv6 && four.code == 0) api.dnsQuery(name, "AAAA") else null
        DnsReply(four.code, four.answers + six?.answers.orEmpty().filter { it.startsWith("AAAA") })
    }

    /** The direct server, over plain DNS. */
    fun direct(server: String, name: String): Check = timed("Direct") { DnsWire.udp(server, name, DnsWire.A) }

    /** The remote server over DNS-over-HTTPS at [url], through KOne's local port when [viaPort] is given. */
    fun remote(url: String, name: String, viaPort: Int?): Check = timed(tr("Remote")) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .proxy(if (viaPort != null) ProxySelector.of(InetSocketAddress("127.0.0.1", viaPort)) else HttpClient.Builder.NO_PROXY).build()
        DnsWire.https(client, url, name, DnsWire.A)
    }

    /** The DoH address of a remote choice as Settings keeps it ("" for the default, a preset's name, or a URL). */
    fun remoteUrl(choice: String): String = when {
        choice.startsWith("https://") -> choice
        else -> "https://${cc.txwd.kone.core.Dns.REMOTE[choice] ?: "1.1.1.1"}/dns-query"
    }

    /** The plain address of a direct choice ("" for the default, a preset's name, or an address). */
    fun directServer(choice: String, region: String = cc.txwd.kone.shared.Region.CHINA): String =
        cc.txwd.kone.core.Dns.DIRECT[choice] ?: choice.ifEmpty { cc.txwd.kone.shared.Region.directDefault(region) }

    /**
     * What the checks add up to, in a sentence: refused, no such name, nothing answers, IPv6 held
     * back, servers that disagree (common when sites answer differently at home and abroad), or fine.
     */
    fun verdict(checks: List<Check>, refused: Boolean, ipv6: Boolean): String {
        if (refused) return tr("Your provider blocks this name, so nothing looks it up")
        val details = checks.map { it.detail }
        return when {
            details.any { it == REFUSED } -> tr("A rule in KOne refuses this name")
            checks.all { it.outcome == Outcome.Fail } -> if (details.all { it == NO_NAME }) tr("No such name: check the spelling") else tr("Nothing answers: check the connection and the DNS servers in Settings")
            details.any { it == NO_NAME } -> tr("Some servers say the name does not exist: one of them may be filtered")
            checks.any { it.outcome == Outcome.Fail } -> tr("Some servers do not answer: the others are enough while they work")
            !ipv6 && checks.any { check -> check.title == tr("Apps") && check.detail.substringBefore(" · ").split(", ").any { ':' in it } } -> tr("IPv6 is off in KOne, so only IPv4 addresses are used")
            // Fake IP: KOne's own answer is a stand-in, and the node looks the real one up where it is.
            checks.any { check -> check.title in setOf(tr("Apps"), "KOne") && addressesOf(check).any(cc.txwd.kone.shared.FakeIp::isFake) } ->
                tr("KOne answers with a stand-in address (Fake IP is on); the node looks up the real one")
            disagree(checks) -> tr("Servers give different addresses; sites often answer by region, and KOne uses its own answer")
            else -> tr("The name resolves normally")
        }
    }

    const val REFUSED = "Refused"
    const val NO_NAME = "No such name"

    private fun addressesOf(check: Check): List<String> =
        if (check.outcome != Outcome.Pass) emptyList() else check.detail.substringBefore(" · ").split(", ").map { it.substringBefore(' ') }

    private fun disagree(checks: List<Check>): Boolean {
        val sets = checks.filter { it.outcome == Outcome.Pass }.map { check -> check.detail.substringBefore(" · ").split(", ").map { it.substringBefore(' ') }.filter { '.' in it }.toSet() }
        return sets.size >= 2 && sets.any { set -> sets.any { other -> set.intersect(other).isEmpty() } }
    }

    /** Runs [ask], timing it, and writes what it gave as a check: up to three addresses and the time taken. */
    private fun timed(title: String, ask: () -> DnsReply): Check {
        val started = System.nanoTime()
        val reply = runCatching(ask).getOrElse { failure ->
            return Check(title, Outcome.Fail, if (failure is java.net.UnknownHostException) NO_NAME else tr("No answer"))
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        return describe(title, reply, millis)
    }

    /** A reply as a check. */
    fun describe(title: String, reply: DnsReply, millis: Long): Check {
        val addresses = reply.answers.filter { !it.startsWith("CNAME") }.map { it.substringAfter(' ') }.distinct()
        return when {
            reply.code == 5 -> Check(title, Outcome.Fail, REFUSED)
            reply.code == 3 -> Check(title, Outcome.Fail, NO_NAME)
            reply.code != 0 -> Check(title, Outcome.Fail, tr("Server error {0}" , reply.code))
            addresses.isEmpty() -> Check(title, Outcome.Warn, tr("No address · {0} ms" , millis))
            else -> Check(title, Outcome.Pass, addresses.take(3).joinToString(", ") + (if (addresses.size > 3) " +${addresses.size - 3}" else "") + " · $millis ms")
        }
    }
}
