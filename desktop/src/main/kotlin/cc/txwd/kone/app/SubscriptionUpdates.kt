package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr

import cc.txwd.kone.shared.NodeFilter
import cc.txwd.kone.shared.SubscriptionContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The person's subscriptions as the Nodes page edits them: added, fetched (at once, when asked,
 * and quietly when due), edited and removed. A fetch keeps only what sing-box accepts beside the
 * console's nodes, and a failure keeps the last good nodes. What it needs of the rest of the App
 * is [Host].
 */
class SubscriptionUpdates(private val scope: CoroutineScope, private val subscriptions: Subscriptions, private val host: Host) {
    interface Host {
        /** The console's configuration, which subscribed nodes join; null without a key. */
        val managed: JsonObject?
        /** KOne's local port while it is the system proxy, for a fetch the network refuses straight out. */
        val proxied: Int?
        /** The subscription the node in use comes from, if any. */
        val inUse: String?
        /** [nodes] that sing-box accepts beside [managed]. */
        fun accepted(managed: JsonObject, nodes: List<JsonObject>): List<JsonObject>
        fun refresh()
        /** The node lists changed: the pages read them again. */
        fun nodesChanged()
        /** The kept nodes changed without a fetch (an edit). */
        fun reload()
        fun restart()
        /** A line on the Nodes page; [error] in the cut ink. */
        fun tell(message: String, error: Boolean)
        /** Said by Windows when the window is away. */
        fun notice(text: String)
    }

    private val updating = HashSet<String>()
    private val warned = HashSet<String>()

    fun updating(id: String) = id in updating

    /**
     * A subscription link typed or pasted, or another client's import link holding one
     * (clash://install-config?url=…): kept and fetched at once, or why not told. False when it is
     * not one KOne fetches (https only).
     */
    fun add(text: String): Boolean {
        val url = SubscriptionContent.linkOf(text) ?: return false
        keep(url)?.let { host.tell(it, error = true) }
        return true
    }

    /** [url], a link KOne fetches, kept and fetched at once; null when it was, else why not. */
    fun keep(url: String): String? {
        val added = subscriptions.add(url, SubscriptionContent.name(url, null))
            ?: return if (subscriptions.all.size >= Subscriptions.MOST) tr("No more subscriptions can be added") else tr("This subscription is already here")
        host.refresh()
        update(added.id, first = true)
        return null
    }

    /** Fetches [id] now; [first] takes the provider's update interval too. */
    fun update(id: String, first: Boolean = false) {
        val managed = host.managed ?: return
        if (!updating.add(id)) return
        host.refresh()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { fetch(id, managed, first) }.getOrNull() }
            updating.remove(id)
            host.nodesChanged()
            outcome?.let { (message, error) -> host.tell(message, error) }
        }
    }

    /** Every subscription due at [now], quietly. */
    fun updateDue(now: Long = System.currentTimeMillis()) {
        subscriptions.all.filter { it.due(now) }.forEach { update(it.id) }
    }

    /**
     * Fetches, reads and checks one subscription, and keeps what sing-box accepts. A failure keeps
     * the last good nodes and is shown on the subscription; a partial success is told in one line.
     */
    private fun fetch(id: String, managed: JsonObject, first: Boolean): Pair<String, Boolean>? {
        val subscription = subscriptions.all.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val fetched = try {
            SubscriptionFetch.fetch(subscription.url, host.proxied)
        } catch (failure: Exception) {
            subscriptions.change(id) { it.copy(tried = now, error = SubscriptionFetch.reason(failure)) }
            return null
        }
        val read = SubscriptionContent.read(fetched.body)
        val kept = host.accepted(managed, read.nodes)
        if (kept.isEmpty()) {
            subscriptions.change(id) { it.copy(tried = now, info = fetched.info ?: it.info, error = read.refusal ?: tr("No usable nodes")) }
            return null
        }
        // A name KOne made up gives way to the provider's own.
        val madeUp = subscription.name == SubscriptionContent.name(subscription.url, null)
        subscriptions.change(id) {
            it.copy(
                name = if (madeUp) fetched.name else it.name,
                hours = if (first) fetched.hours ?: it.hours else it.hours,
                nodes = kept, info = fetched.info ?: it.info, updated = now, tried = now, error = null,
            )
        }
        // The provider's terms running out, said once a session, before the nodes simply stop.
        subscriptions.all.firstOrNull { it.id == id }?.warning(now)?.let { warning -> if (warned.add(warning)) host.notice(warning) }
        val left = read.nodes.size - kept.size + read.refused
        return if (left > 0) "${kept.size} nodes kept, $left left out" to false else null
    }

    /** A subscription's name, link, interval and the words that leave nodes out; false when the link is not one KOne fetches. A new link is fetched at once. */
    fun edit(id: String, name: String, url: String, hours: Int, leaveOut: String = ""): Boolean {
        if (!SubscriptionContent.acceptable(url) || hours !in 0..720) return false
        val before = subscriptions.all.firstOrNull { it.id == id } ?: return false
        subscriptions.change(id) { it.copy(name = name.ifBlank { it.name }.take(32), url = url, hours = hours, leaveOut = NodeFilter.clean(leaveOut)) }
        host.reload()
        if (url != before.url) update(id)
        return true
    }

    fun remove(id: String) {
        val used = host.inUse == id
        if (!subscriptions.remove(id)) return
        host.nodesChanged()
        if (used) host.restart()
    }
}
