package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr


import cc.txwd.kone.shared.Why
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.Probe
import cc.txwd.kone.core.RouteProbe
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.ui.DnsLookupState
import cc.txwd.kone.ui.SiteCheckState
import cc.txwd.kone.ui.ToolsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Tools' questions about one name: DNS lookup (the name every way KOne can ask) and Site check
 * (where the running core sends a site now, and which way serves it better, tried in a core of
 * KOne's own). Each shows its rows as they answer, in Tools' state. What it needs of the rest of
 * the App is [Host].
 */
class SiteTools(private val scope: CoroutineScope, private val preferences: Preferences, private val host: Host) {
    interface Host {
        val tools: ToolsState
        fun update(change: (ToolsState) -> ToolsState)
        /** The console's configuration, whose refusals are never asked for. */
        val console: JsonObject?
        /** The nodes as the template carries them, the console's and the person's together. */
        val merged: JsonObject?
        /** The rules folder the App's own cores read. */
        val rules: String
        /** The running core's (or the delay core's) API, for KOne's own DNS. */
        val anyCore: ClashApi?
        /** The running core's API while connected. */
        val live: ClashApi?
        /** KOne's adapter carries everything now. */
        val adapterOn: Boolean
        /** KOne's local port while it is the system proxy. */
        val proxied: Int?
        val nodes: List<SourceNode>
        /** Each node's last delay, null for no answer. */
        fun delay(tag: String): Int?
        /** The node carrying traffic now. */
        fun inUse(): String?
        fun nameOf(tag: String): String
        /** Puts a site on Direct sites or Proxied sites; false when it is not a site. */
        fun addSite(site: String, direct: Boolean): Boolean
        fun hint(text: String)
        /** A loopback port free now, and a secret, for a core of KOne's own. */
        fun freePort(): Int
        fun token(): String
        /** The installed sing-box, if any. */
        val core: File?
    }

    /**
     * Looks [input] up every way KOne can, at once, showing each as it answers: as programs see it,
     * through KOne's own DNS (while it runs), from the direct server (unless the adapter is on, when
     * KOne answers for it) and from the remote one. False when [input] is not a domain name.
     */
    fun lookUp(input: String): Boolean {
        val name = DnsLookup.clean(input) ?: return false
        if (host.tools.lookup?.running == true) return true
        val refused = DnsLookup.refused(name, host.console)
        val ipv6 = preferences.ipv6
        val api = host.anyCore
        val adapterOn = host.adapterOn
        val viaPort = host.proxied
        val ways = buildList<Pair<String, () -> Check>> {
            add(tr("Apps") to { DnsLookup.system(name) })
            if (api != null) add("KOne" to { DnsLookup.core(api, name, ipv6) })
            if (!adapterOn) add("Direct" to { DnsLookup.direct(DnsLookup.directServer(preferences.directDns, preferences.region), name) })
            add(tr("Remote") to { DnsLookup.remote(DnsLookup.remoteUrl(preferences.remoteDns), name, viaPort) })
        }
        if (refused) {
            host.update { it.copy(lookup = DnsLookupState(name, emptyList(), running = false, verdict = DnsLookup.verdict(emptyList(), refused = true, ipv6 = ipv6))) }
            return true
        }
        val done = arrayOfNulls<Check>(ways.size)
        fun show() {
            val checks = ways.mapIndexed { i, (title, _) -> done[i] ?: Check(title, Outcome.Running) }
            val finished = done.all { it != null }
            host.update { it.copy(lookup = DnsLookupState(name, checks, running = !finished, verdict = if (finished) DnsLookup.verdict(checks, refused = false, ipv6 = ipv6) else null)) }
        }
        show()
        scope.launch {
            coroutineScope {
                ways.forEachIndexed { i, (_, ask) ->
                    launch {
                        done[i] = withContext(Dispatchers.IO) { ask() }
                        show()
                    }
                }
            }
        }
        return true
    }

    /**
     * Site check: [input] tried straight out and through the node in use and the best few others,
     * in a core of KOne's own (the running core keeps every test for Automatic, and a slow site
     * must not move it). While connected, the running core is first asked where it sends the site
     * now. Sites the console refuses are never tried. False when it is not a name.
     */
    fun checkSite(input: String): Boolean {
        val site = DnsLookup.clean(input) ?: return false
        if (host.tools.site?.running == true) return true
        fun settle(verdict: String) {
            host.update { it.copy(site = SiteCheckState(site, emptyList(), running = false, verdict = verdict)) }
        }
        if (DnsLookup.refused(site, host.console)) {
            settle(tr("Your provider blocks {0}" , site))
            return true
        }
        val core = host.core
        val source = host.merged
        if (core == null || source == null) {
            settle(if (core == null) tr("Site checks need KOne installed") else tr("Waiting for the console's nodes"))
            return true
        }
        val nodes = host.nodes
        val current = host.inUse()?.takeIf { it != KoneConfig.AUTO_TAG && nodes.any { node -> node.tag == it } }
        val others = nodes.map { it.tag }.filter { it != current }.sortedBy { host.delay(it) ?: Int.MAX_VALUE }
        val picked = (listOfNotNull(current) + others).take(4)
        val ways = listOf("direct" to tr("Straight out")) + picked.map { it to host.nameOf(it) }
        val live = host.live
        val viaPort = host.proxied
        val lists = Why.Lists(preferences.bypass, preferences.direct, preferences.proxied)
        val rules = host.rules
        val first = listOfNotNull(live?.let { Check(tr("Now"), Outcome.Running) })
        host.update { it.copy(site = SiteCheckState(site, first + ways.map { (_, title) -> Check(title, Outcome.Running) }, running = true)) }
        scope.launch {
            val now = live?.let { api -> async(Dispatchers.IO) { SiteCheck.now(RouteProbe.find(site, viaPort, api), lists, host::nameOf) } }
            val tries = withContext(Dispatchers.IO) {
                val api = CoreApi(host.freePort(), host.token())
                val config = runCatching { KoneConfig.build(source, rules, api, tun = false, mode = "Global", region = preferences.region).config.toString() }.getOrNull()
                val tester = Probe(core)
                try {
                    val up = config != null && tester.start(config) && ClashApi(api).let { clash -> (0 until 50).any { clash.answers().also { ok -> if (!ok) Thread.sleep(200) } } }
                    coroutineScope {
                        ways.map { (tag, title) ->
                            async { SiteCheck.Try(tag, title, if (up) ClashApi(api).delay(tag, 8_000, SiteCheck.url(site)) else null) }
                        }.awaitAll()
                    }
                } finally {
                    tester.stop()
                }
            }
            val way = SiteCheck.way(tries.first().millis, tries.drop(1).map { it.millis })
            val rows = listOfNotNull(now?.await()) + SiteCheck.rows(tries)
            host.update { it.copy(site = SiteCheckState(site, rows, running = false, verdict = SiteCheck.verdict(site, way), way = way)) }
        }
        return true
    }

    /** Keeps what a site check found: the site on Direct sites or Proxied sites (Rules), ahead of the person's own rules. */
    fun keepSite(direct: Boolean) {
        val site = host.tools.site?.site ?: return
        if (host.addSite(site, direct)) {
            host.hint(if (direct) "$site now goes straight out" else "$site now goes through the node")
            host.update { it.copy(site = null) }
        }
    }

    fun clearSite() {
        if (host.tools.site?.running != true) host.update { it.copy(site = null) }
    }

    fun clearLookup() {
        if (host.tools.lookup?.running != true) host.update { it.copy(lookup = null) }
    }
}
