package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Host
import cc.txwd.kone.shared.Hosts
import cc.txwd.kone.shared.NameServer
import cc.txwd.kone.shared.NameServers
import cc.txwd.kone.shared.Region
import cc.txwd.kone.shared.Rule
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the routing choices live (Settings → Routing) and how they reach the configurations: the
 * service applies them on every start (with Home, in [HomeSettings.prepare]), and a change
 * rewrites the saved configurations and reloads a running service, so it takes effect at once.
 */
object RoutingSettings {
    fun current(): Routing.Choices = Routing.Choices(
        direct = lines(Settings.knineRoutingDirect),
        proxied = lines(Settings.knineRoutingProxied),
        rules = rules(Settings.knineRoutingRules),
        blockAds = Settings.knineRoutingBlockAds,
        unmatchedDirect = Settings.knineRoutingUnmatchedDirect,
        remoteDns = Settings.knineRoutingRemoteDns.takeIf { it in Routing.REMOTE }.orEmpty(),
        directDns = Settings.knineRoutingDirectDns.takeIf { it in Routing.DIRECT }.orEmpty(),
        ipv6 = Settings.knineRoutingIpv6,
        region = Region.clean(Settings.knineRoutingRegion),
        hosts = lines(Settings.knineRoutingHosts).mapNotNull(Hosts::parse),
        nameServers = lines(Settings.knineRoutingNameServers).mapNotNull(NameServers::parse),
    )

    /** Fixed answers as kept and shown: "name address", one a line, cleaned and bounded. */
    fun hostLines(hosts: List<Host>): String = Hosts.flatten(Hosts.clean(hosts)).joinToString("\n") { "${it.name} ${it.address}" }

    /** The person's own servers as kept and shown: "domain server", one a line. */
    fun nameServerLines(servers: List<NameServer>): String =
        NameServers.flatten(NameServers.clean(servers)).joinToString("\n") { "${it.domain} ${it.server}" }

    private fun lines(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun rules(text: String): List<Rule> = runCatching {
        val array = JSONArray(text)
        OwnRules.clean((0 until array.length()).mapNotNull { OwnRules.fromJson(array.optJSONObject(it)) })
    }.getOrDefault(emptyList())

    /** Keeps [choices] (sites cleaned), rewrites the saved configurations and reloads the service. */
    suspend fun save(choices: Routing.Choices) = withContext(Dispatchers.IO) {
        Settings.knineRoutingDirect = Routing.sites(choices.direct).joinToString("\n")
        Settings.knineRoutingProxied = Routing.sites(choices.proxied).joinToString("\n")
        Settings.knineRoutingRules = JSONArray(OwnRules.clean(choices.rules).map(OwnRules::toJson)).toString()
        Settings.knineRoutingBlockAds = choices.blockAds
        Settings.knineRoutingUnmatchedDirect = choices.unmatchedDirect
        Settings.knineRoutingRemoteDns = choices.remoteDns.takeIf { it in Routing.REMOTE }.orEmpty()
        Settings.knineRoutingDirectDns = choices.directDns.takeIf { it in Routing.DIRECT }.orEmpty()
        Settings.knineRoutingIpv6 = choices.ipv6
        Settings.knineRoutingRegion = Region.clean(choices.region)
        Settings.knineRoutingHosts = hostLines(choices.hosts)
        Settings.knineRoutingNameServers = nameServerLines(choices.nameServers)
        HomeSettings.refreshSaved()
        // A service that is not running has nothing to reload.
        runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
        // A new category list is fetched now; its rules join once it is kept.
        CategoryListKeeper.update()
    }
}
