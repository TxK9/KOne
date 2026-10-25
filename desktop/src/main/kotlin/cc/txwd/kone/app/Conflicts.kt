package cc.txwd.kone.app

import java.net.NetworkInterface

/**
 * Other software that takes traffic before KOne or beside it, the most common reason a client
 * "connects but nothing works": another VPN's adapter that is up (WireGuard, Tailscale, a work
 * VPN, another proxy client's TUN), or Windows' proxy pointing at another program.
 */
object Conflicts {
    /** A network adapter as Windows lists it, with its addresses. */
    data class Adapter(val name: String, val description: String, val up: Boolean, val addresses: List<String> = emptyList())

    /** Words in an adapter's name or description, and what people call the software behind it. */
    private val KNOWN = listOf(
        "tailscale" to "Tailscale", "wireguard" to "WireGuard", "zerotier" to "ZeroTier", "openvpn" to "OpenVPN",
        "tap-windows" to "OpenVPN (TAP)", "warp" to "Cloudflare WARP", "anyconnect" to "Cisco AnyConnect",
        "globalprotect" to "GlobalProtect", "pangp" to "GlobalProtect", "fortinet" to "FortiClient", "forticlient" to "FortiClient",
        "juniper" to "Juniper", "pulse secure" to "Pulse Secure", "wiresock" to "WireSock", "outline" to "Outline",
        "clash" to "a Clash client", "mihomo" to "a Clash client", "meta tunnel" to "a Clash client",
        "sing-tun" to "a sing-box client", "sing-box" to "a sing-box client", "singbox" to "a sing-box client", "nekoray" to "NekoRay", "hiddify" to "Hiddify",
        "v2ray" to "a V2Ray client", "xray" to "an Xray client", "wintun" to "another VPN (Wintun)",
    )

    /**
     * The other VPNs whose adapters are up, by name, each once. KOne's own adapter is known by its
     * address (the template's 172.19.0.1), as its description is sing-tun's, like other sing-box clients'.
     */
    fun vpns(adapters: List<Adapter>): List<String> = adapters.asSequence()
        .filter { it.up && OWN_ADDRESS !in it.addresses }
        .mapNotNull { adapter ->
            val text = "${adapter.name} ${adapter.description}".lowercase()
            KNOWN.firstOrNull { (word, _) -> word in text }?.second
        }
        .distinct().toList()

    /** Where Windows' proxy points when it is not KOne's own port ([own]), or null: an address, or a PAC script's. */
    fun proxyElsewhere(setting: SystemProxy.Setting?, own: Int?): String? {
        setting ?: return null
        if (setting.flags and PROXY_TYPE_PROXY != 0) {
            val server = setting.server?.trim().orEmpty()
            if (server.isNotEmpty() && (own == null || server != "127.0.0.1:$own")) return server
        }
        if (setting.flags and PROXY_TYPE_AUTO_PROXY_URL != 0) return setting.pac?.trim()?.takeIf { it.isNotEmpty() }
        return null
    }

    /** The adapters on this computer. */
    fun adapters(): List<Adapter> = runCatching {
        NetworkInterface.networkInterfaces().map { adapter ->
            Adapter(
                adapter.name.orEmpty(), adapter.displayName.orEmpty(), runCatching { adapter.isUp }.getOrDefault(false),
                adapter.inetAddresses().map { it.hostAddress.substringBefore('%') }.toList(),
            )
        }.toList()
    }.getOrDefault(emptyList())

    /** As a diagnosis check: what else is on, with KOne's own system-proxy port ([own]) when it uses one. */
    fun check(adapters: List<Adapter>, proxy: SystemProxy.Setting?, own: Int?): Check {
        val vpns = vpns(adapters)
        val elsewhere = proxyElsewhere(proxy, own)
        val found = vpns + listOfNotNull(elsewhere?.let { "Windows' proxy at $it" })
        return if (found.isEmpty()) Check(TITLE, Outcome.Pass, "No other VPN or proxy") else Check(TITLE, Outcome.Warn, found.joinToString(", "))
    }

    const val TITLE = "Others"
    /** KOne's adapter's address (KoneConfig and desktop/service/plan.go). */
    const val OWN_ADDRESS = "172.19.0.1"
    private const val PROXY_TYPE_PROXY = 2
    private const val PROXY_TYPE_AUTO_PROXY_URL = 4
}
