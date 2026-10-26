package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr

import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.shared.NodeFilter
import cc.txwd.kone.shared.SubscriptionContent
import cc.txwd.kone.ui.ToolsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings → Backup: the person's setup written out (never the key or the device's binding), and
 * a backup restored into what is here. Every value goes through its usual checks; lists are
 * joined rather than replaced, and restoring the same backup twice adds nothing the second time.
 * What it needs of the rest of the App is [Host].
 */
class Backups(
    private val scope: CoroutineScope,
    private val preferences: Preferences,
    private val subscriptions: Subscriptions,
    private val imports: Imports,
    private val host: Host,
) {
    interface Host {
        val tools: ToolsState
        fun update(change: (ToolsState) -> ToolsState)
        /** The console's configuration, which imported nodes join; null without a key. */
        val managed: JsonObject?
        /** [nodes] that sing-box accepts beside [managed]. */
        fun accepted(managed: JsonObject, nodes: List<JsonObject>): List<JsonObject>
        /** The Rules and Network pages read what is kept again. */
        fun showSettings()
        /** The category lists the rules name are fetched. */
        fun updateLists()
        /** A subscription restored is fetched. */
        fun fetch(id: String)
        fun nodesChanged()
        fun restart()
    }

    /** The person's setup as a backup file's text. */
    fun write(): String = Backup(
        mode = preferences.mode, way = preferences.way, bypass = preferences.bypass, direct = preferences.direct, proxied = preferences.proxied,
        rules = preferences.rules, blockAds = preferences.blockAds, remoteDns = preferences.remoteDns, directDns = preferences.directDns,
        ipv6 = preferences.ipv6, fakeIp = preferences.fakeIp, failover = preferences.failover, hosts = preferences.hosts, unmatchedDirect = preferences.unmatchedDirect,
        nameServers = preferences.nameServers, region = preferences.region,
        subscriptions = subscriptions.all.map { Backup.Entry(it.name, it.url, it.hours, it.leaveOut) },
        imported = imports.all,
    ).write()

    /**
     * Restores a backup into what is here: subscriptions added and fetched, imported nodes checked
     * by sing-box. What happened, in a line; null when [text] is not a KOne backup.
     */
    fun restore(text: String): String? {
        val backup = Backup.read(text) ?: return null
        backup.mode?.takeIf { it in KoneConfig.MODES }?.let { mode ->
            preferences.mode = mode
            host.update { it.copy(mode = mode) }
        }
        backup.way?.takeIf { it == AppModel.WAY_ADAPTER || it == AppModel.WAY_PROXY }?.let { way ->
            preferences.way = way
            host.update { it.copy(way = way) }
        }
        preferences.bypass = (preferences.bypass + backup.bypass).distinctBy { it.lowercase() }
        preferences.direct = (preferences.direct + backup.direct.mapNotNull(KoneConfig::site)).distinct()
        preferences.proxied = (preferences.proxied + backup.proxied.mapNotNull(KoneConfig::site)).filter { it !in preferences.direct }.distinct()
        preferences.rules = (preferences.rules + backup.rules).distinct()
        backup.blockAds?.let { preferences.blockAds = it }
        backup.remoteDns?.let { preferences.remoteDns = it }
        backup.directDns?.let { preferences.directDns = it }
        backup.ipv6?.let { preferences.ipv6 = it }
        backup.fakeIp?.let { preferences.fakeIp = it }
        backup.failover?.let { preferences.failover = it }
        if (backup.hosts.isNotEmpty()) preferences.hosts = (preferences.hosts + backup.hosts).distinct()
        if (backup.nameServers.isNotEmpty()) preferences.nameServers = (preferences.nameServers + backup.nameServers).distinct()
        backup.region?.let { preferences.region = it }
        backup.unmatchedDirect?.let { preferences.unmatchedDirect = it }
        host.update { it.copy(bypass = preferences.bypass) }
        host.showSettings()
        if (backup.rules.any { it.kind == "set" }) host.updateLists()
        val added = backup.subscriptions.mapNotNull { entry ->
            if (!SubscriptionContent.acceptable(entry.url)) return@mapNotNull null
            subscriptions.add(entry.url, entry.name.take(32))?.also {
                subscriptions.change(it.id) { kept -> kept.copy(hours = entry.hours.coerceIn(0, 720), leaveOut = NodeFilter.clean(entry.leaveOut)) }
            }
        }
        added.forEach { host.fetch(it.id) }
        val managed = host.managed
        if (managed != null && backup.imported.isNotEmpty()) {
            scope.launch {
                val kept = withContext(Dispatchers.IO) {
                    val checked = host.accepted(managed, backup.imported.map { JsonObject(it - "tag") })
                    imports.addOutbounds(checked.map { node -> node to backup.imported.firstOrNull { JsonObject(it - "tag") == node }?.let { (it["tag"] as? JsonPrimitive)?.content } })
                }
                if (kept > 0) host.nodesChanged()
            }
        }
        host.restart()
        return tr("Backup restored")
    }
}
