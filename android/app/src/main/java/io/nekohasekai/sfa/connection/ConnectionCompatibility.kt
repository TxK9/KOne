package io.nekohasekai.sfa.connection

import org.json.JSONArray
import org.json.JSONObject

internal enum class CallingDns(val strategy: String?, val label: String) {
    Original(null,"Use profile settings"), PreferIpv4("prefer_ipv4","Prefer IPv4"), Ipv4Only("ipv4_only","IPv4 only"), PreferIpv6("prefer_ipv6","Prefer IPv6")
}
internal data class CallingOptions(val enabled: Boolean = false, val dns: CallingDns = CallingDns.Original, val mtu: Int = 0, val videoTcp: Boolean = false) {
    init { require(mtu in setOf(0,1280,1400,1500)) }
}

/** Country/carrier independent. It affects only traffic that enters KOne's tunnel.
 * Saved source files, selected proxy, detour chains, home routes and managed refusals stay intact.
 */
internal object ConnectionCompatibility {
    fun apply(content: String, options: CallingOptions): String {
        if (!options.enabled) return content
        val config = JSONObject(content)
        val outbounds = config.optJSONArray("outbounds") ?: error("Configuration has no proxy outbound")
        val proxy = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == "proxy" && it.optString("type") == "selector" }
            ?: error("This custom configuration cannot be applied automatically.")
        require(proxy.optJSONArray("outbounds")?.length()?.let { it > 0 } == true)
        val route = config.optJSONObject("route") ?: error("Configuration has no route")
        val routeRules = route.optJSONArray("rules") ?: error("Configuration has no routing rules")
        val dns = config.optJSONObject("dns") ?: error("Configuration has no DNS settings")
        val servers = dns.optJSONArray("servers") ?: error("Configuration has no DNS server")
        val server = (0 until servers.length()).mapNotNull { servers.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == "proxy-dns" && it.optString("detour") == "proxy" }
            ?: error("These custom DNS settings cannot be applied automatically.")
        val tun = config.optJSONArray("inbounds")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("type") == "tun" }
        }.orEmpty()
        require(tun.isNotEmpty()) { "Configuration has no TUN inbound" }
        // Standard KOne profiles have an explicit Direct mode. Preserve it and every earlier
        // refusal/home rule; the compatibility override belongs before geographical split rules.
        require((0 until routeRules.length()).any { routeRules.optJSONObject(it)?.optString("clash_mode") == "Direct" }) {
            "This custom route cannot be applied automatically."
        }
        val callingRoute = JSONObject().put("network","udp").put("port",JSONArray(listOf(500,4500))).put("outbound","proxy")
        route.put("rules", beforeSplit(routeRules,listOf(callingRoute)))
        val dnsRules = dns.optJSONArray("rules") ?: JSONArray()
        val gateway = JSONObject().put("type","logical").put("mode","or").put("rules",JSONArray()
            .put(JSONObject().put("domain_suffix",JSONArray(listOf("3gppnetwork.org"))))
            .put(JSONObject().put("domain_keyword",JSONArray(listOf("epdg")))))
            .put("server",server.getString("tag"))
        dns.put("rules",beforeSplit(dnsRules,listOf(gateway)))
        options.dns.strategy?.let { dns.put("strategy",it) }
        if (options.mtu > 0) tun.forEach { it.put("mtu",options.mtu) }
        return config.toString()
    }
    private fun beforeSplit(rules: JSONArray, added: List<JSONObject>): JSONArray {
        val result = JSONArray()
        var inserted = false
        val protectedThrough = (0 until rules.length()).filter { index ->
            val rule=rules.optJSONObject(index)
            rule?.optString("action") == "reject" || rule?.optString("clash_mode") == "Direct" ||
                (rule?.optBoolean("ip_is_private") == true && rule.optString("outbound") == "direct")
        }.maxOrNull() ?: -1
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: error("Invalid routing rule")
            // Reject rules are always kept ahead of this addition. Home and Direct remain ahead
            // in the app template. No existing rule is edited, removed or reordered.
            if (!inserted && index > protectedThrough && rule.optString("action") != "reject" &&
                (rule.optString("clash_mode") == "Global" || rule.has("rule_set") || rule.has("ip_is_private"))) {
                added.forEach { result.put(it) };inserted = true
            }
            result.put(rule)
        }
        if (!inserted) added.forEach { result.put(it) }
        return result
    }
}
