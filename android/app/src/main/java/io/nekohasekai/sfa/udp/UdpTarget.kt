package io.nekohasekai.sfa.udp

import org.json.JSONObject

internal data class UdpTarget(val profileId: Long, val name: String, val tag: String, val epoch: Long, val network: Long, val tcpOnly: Boolean)

/** Resolves an explicit proxy, preserving its detour chain in the running core. No direct fallback. */
internal object UdpTargets {
    private val proxies = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria", "hysteria2", "tuic", "anytls", "ssh", "http", "naive")
    fun resolve(profile: RunningUdpProfile, selected: Map<String, String>): UdpTarget? {
        val json = runCatching { JSONObject(profile.config) }.getOrNull() ?: return null
        val array = json.optJSONArray("outbounds") ?: return null
        val nodes = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val byTag = nodes.associateBy { it.optString("tag") }
        val root = if (byTag["proxy"]?.optString("type") == "selector") "proxy"
            else json.optJSONObject("route")?.optString("final").orEmpty().ifBlank { nodes.firstOrNull()?.optString("tag").orEmpty() }
        val visited = mutableSetOf<String>()
        fun follow(tag: String): JSONObject? {
            if (tag.isBlank() || !visited.add(tag) || visited.size > 16) return null
            val node = byTag[tag] ?: return null
            return when (node.optString("type")) {
                in proxies -> node
                "selector", "urltest" -> {
                    // A live selector must have reported its choice; its saved default may differ.
                    val choice = selected[tag]?.takeIf { it.isNotBlank() } ?: return null
                    val members = node.optJSONArray("outbounds") ?: return null
                    if ((0 until members.length()).none { members.optString(it) == choice }) return null
                    follow(choice)
                }
                else -> null
            }
        }
        val node = follow(root) ?: return null
        val configured = when (val value = node.opt("network")) {
            is String -> value.split(',','\n').map { it.trim() }.filter { it.isNotEmpty() }
            is org.json.JSONArray -> (0 until value.length()).map { value.optString(it) }
            else -> emptyList()
        }
        return UdpTarget(profile.id, profile.name, node.getString("tag"), profile.epoch, profile.network,
            configured.isNotEmpty() && "udp" !in configured)
    }
}
