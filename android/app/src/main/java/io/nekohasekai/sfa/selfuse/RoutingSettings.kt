package io.nekohasekai.sfa.selfuse

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
    )

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
        HomeSettings.refreshSaved()
        // A service that is not running has nothing to reload.
        runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
    }
}
