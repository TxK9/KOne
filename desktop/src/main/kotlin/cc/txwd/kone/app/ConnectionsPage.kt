package cc.txwd.kone.app

import cc.txwd.kone.core.Carried
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.ui.ToolsState
import cc.txwd.kone.ui.UsageDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the core carries, as Tools shows it and as KOne learns from it: the live connections and
 * why each went its way, usage by node, program and day, and what real traffic says (a node in
 * use whose connections stall, a site that keeps failing straight out). What it needs of the rest
 * of the App is [Host].
 */
class ConnectionsPage(private val scope: CoroutineScope, private val traffic: Traffic, private val host: Host) {
    interface Host {
        val tools: ToolsState
        fun update(change: (ToolsState) -> ToolsState)
        /** The running core, while connected. */
        val api: ClashApi?
        /** The person's program and site lists, for saying why a connection went its way. */
        val lists: Why.Lists
        val mode: String
        fun inUse(): String?
        fun nameOf(tag: String): String
        /** The node in use is stalling: counted against it, and tested now. */
        fun stalling(tag: String)
        /** Puts [site] on Direct sites or Proxied sites; false when it was not one or is there already. */
        fun addSite(site: String, direct: Boolean): Boolean
        fun hint(text: String)
        /** Said by Windows when the window is away. */
        fun notice(text: String)
    }

    /** What real traffic says about sites and nodes (see ConnectionWatch). */
    private val watched = ConnectionWatch()
    private var stalledAt = 0L
    private val advised = HashSet<String>()
    private var watching: Job? = null

    /** Whether Tools is showing the connections now, which then count the traffic too. */
    val showing get() = watching != null

    /** While Tools is open and KOne connected, what the core carries, once a second. */
    fun watch(on: Boolean) {
        watching?.cancel()
        watching = null
        if (!on) return
        watching = scope.launch {
            while (isActive) {
                val api = host.api
                val carried = if (api == null) null else withContext(Dispatchers.IO) { api.connections() }
                carried?.let(::count)
                // Why each went its way, in the person's terms.
                val lists = host.lists
                host.update { tools -> tools.copy(carried = carried?.let { it.copy(connections = it.connections.map { one -> one.copy(why = Why.explain(one.rule, lists)) }) }) }
                delay(1000)
            }
        }
    }

    /** One look at what the core carries: usage counted, stalls and failing sites noticed. */
    fun count(carried: Carried) {
        traffic.sample(carried.connections, host::nameOf)
        host.tools.details?.let { shown -> host.update { it.copy(details = details(shown.span)) } }
        val now = System.currentTimeMillis()
        watched.sample(carried.connections, now)
        // Passive health: stalling connections through the node in use count against it, and it is tested now.
        host.inUse()?.let { tag ->
            if (watched.stalling(tag, now) && now - stalledAt >= 20_000) {
                stalledAt = now
                host.stalling(tag)
            }
        }
        // Sites failing straight out, in Rule mode, where sending them through the node is the person's to choose.
        val advice = if (host.mode == "Rule") watched.advice().take(3) else emptyList()
        advice.filter { advised.add(it.site) }.forEach { host.notice("${it.site} does not answer straight out; Tools can send it through the node") }
        if (advice != host.tools.siteAdvice) host.update { it.copy(siteAdvice = advice) }
    }

    /** A connection's site sent the other way from now on: on Direct sites (every mode) or Proxied sites (Rule mode). */
    fun sendSite(site: String, direct: Boolean) {
        if (host.addSite(site, direct)) host.hint(if (direct) "$site now goes straight out" else "$site now goes through the node")
    }

    /** Takes an advice: the site goes on Proxied sites (Rules), so Rule mode sends it through the node. */
    fun takeAdvice(site: String) {
        watched.dismiss(site)
        if (host.addSite(site, direct = false)) host.hint("$site now goes through the node")
        host.update { tools -> tools.copy(siteAdvice = tools.siteAdvice.filter { it.site != site }) }
    }

    fun dismissAdvice(site: String) {
        watched.dismiss(site)
        host.update { tools -> tools.copy(siteAdvice = tools.siteAdvice.filter { it.site != site }) }
    }

    /** Usage by node, by program and by day over [span] days, or put away for null. */
    fun showUsage(span: Int?) {
        host.update { it.copy(details = span?.takeIf { days -> days in setOf(1, 7, 30) }?.let(::details)) }
    }

    private fun details(span: Int) = UsageDetails(span, traffic.top(TrafficBy.Node, span), traffic.top(TrafficBy.App, span), traffic.daily(span))

    /** Ends one connection, or all of them when [id] is null. */
    fun close(id: String?) {
        val api = host.api ?: return
        scope.launch(Dispatchers.IO) { api.close(id) }
    }
}
