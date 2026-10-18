package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject

/** One sing-box service owns the proxy and, when Home is on, the private Tailscale endpoint. */
object PairedProfile {
    const val HOME_TAG = "home-tailnet"
    const val PROXY_TAG = "proxy"
    private fun obj(vararg values: Pair<String, Any>) = JSONObject().apply { values.forEach { put(it.first, it.second) } }
    private fun array(vararg values: Any) = JSONArray().apply { values.forEach { put(it) } }

    /** A configuration around the node a share link describes; see [ShareLinks] and [HomeLink]. */
    fun fromLink(link: String, home: HomeLink.Home, rulesDirectory: String = "rules"): String = build(ShareLinks.parse(link).outbound, home, rulesDirectory)

    /**
     * The node outbound of a configuration built here, without its tag, as text with its keys
     * in order: the same link gives the same key whoever wrote the file, so a node is not
     * imported twice. Null for an outbound that is not such a node.
     */
    fun nodeKey(outbound: JSONObject?): String? {
        if (outbound == null || outbound.optString("tag") != ShareLinks.NODE_TAG) return null
        val copy = JSONObject(outbound.toString()).apply { remove("tag") }
        return sorted(copy).toString()
    }

    private fun sorted(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> value.keys().asSequence().sorted().forEach { out.put(it, sorted(value.get(it))) } }
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(sorted(value.get(it))) } }
        else -> value
    }

    /** The proxy around [node], with Home added as [home] says. */
    fun build(node: JSONObject, home: HomeLink.Home, rulesDirectory: String = "rules"): String =
        buildGroup(listOf(node), emptyList(), home, rulesDirectory)

    /**
     * The proxy around several nodes to choose from, the first tagged [ShareLinks.NODE_TAG] and
     * selected by default. [support] are outbounds the nodes reach through `detour` (a relay
     * chain); they are kept but not offered as choices. Tags must already be unique.
     * [refused] are rules refusing domain names (see ManagedConfig.refusals): they apply in every
     * mode, to connections and to DNS alike, so a browser cannot get round them by its own lookup.
     */
    fun buildGroup(members: List<JSONObject>, support: List<JSONObject>, home: HomeLink.Home, rulesDirectory: String = "rules", refused: List<JSONObject> = emptyList()): String {
        require(members.isNotEmpty()) { "An actual proxy node is required." }
        members.forEach { node -> require(node.optString("type") != "direct" && node.optString("type") != "block") { "An actual proxy node is required." } }
        val node = members.first()
        if (node.optString("tag").isBlank()) node.put("tag", ShareLinks.NODE_TAG)
        val choices = array(*members.map { it.optString("tag") }.toTypedArray())
        val refusals = { refused.map { JSONObject(it.toString()) }.toTypedArray() }
        val routes = array(
            obj("action" to "sniff"),
            obj("protocol" to "dns", "action" to "hijack-dns"),
            // Home goes in right here (HomeLink.apply), ahead of these refusals and every mode.
            *refusals(),
            obj("clash_mode" to "Direct", "outbound" to "direct"),
            obj("clash_mode" to "Global", "outbound" to PROXY_TAG),
            obj("ip_is_private" to true, "outbound" to "direct"),
            obj("clash_mode" to "Rule", "rule_set" to array("geosite-cn", "geoip-cn"), "outbound" to "direct"),
            obj("clash_mode" to "Rule", "outbound" to PROXY_TAG),
        )
        val rulesets = array(
            obj("type" to "remote", "tag" to "geosite-cn", "format" to "binary",
                "url" to "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs", "initial_path" to "$rulesDirectory/geosite-cn.srs", "http_client" to obj("detour" to PROXY_TAG, "domain_resolver" to "local-dns")),
            obj("type" to "remote", "tag" to "geoip-cn", "format" to "binary",
                "url" to "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs", "initial_path" to "$rulesDirectory/geoip-cn.srs", "http_client" to obj("detour" to PROXY_TAG, "domain_resolver" to "local-dns")),
        )
        val config = obj(
            "log" to obj("level" to "warn"),
            "dns" to obj(
                "servers" to array(
                    obj("type" to "udp", "tag" to "local-dns", "server" to "223.5.5.5"),
                    obj("type" to "https", "tag" to "proxy-dns", "server" to "1.1.1.1", "detour" to PROXY_TAG),
                ),
                "rules" to array(
                    *refusals(),
                    obj("clash_mode" to "Direct", "server" to "local-dns"),
                    obj("clash_mode" to "Global", "server" to "proxy-dns"),
                    obj("rule_set" to array("geosite-cn"), "server" to "local-dns"),
                ), "final" to "proxy-dns", "strategy" to "ipv4_only",
            ),
            "inbounds" to array(obj("type" to "tun", "tag" to "tun-in", "address" to array("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                "auto_route" to true)),
            "outbounds" to array(
                obj("type" to "selector", "tag" to PROXY_TAG, "outbounds" to choices, "default" to node.optString("tag")),
                *members.toTypedArray(), *support.toTypedArray(), obj("type" to "direct", "tag" to "direct"),
            ),
            "route" to obj("rules" to routes, "rule_set" to rulesets, "auto_detect_interface" to true,
                "default_domain_resolver" to "local-dns", "final" to PROXY_TAG),
            "experimental" to obj("cache_file" to obj("enabled" to true), "clash_api" to obj("default_mode" to "Rule")),
        )
        HomeLink.apply(config, home)
        val content = config.toString(2)
        if (home.enabled) requirePaired(content)
        return content
    }

    fun requirePaired(content: String) {
        val config = try { JSONObject(content) } catch (_: Exception) { throw IllegalArgumentException("Invalid configuration format") }
        val endpoints = config.optJSONArray("endpoints") ?: JSONArray()
        val tags = (0 until endpoints.length()).mapNotNull { index ->
            endpoints.optJSONObject(index)?.takeIf { it.optString("type") == "tailscale" }?.optString("tag")
        }.filter { it.isNotBlank() }
        require(tags.isNotEmpty()) { "Tailscale is disabled in this profile. Create a paired Proxy and Home profile first." }
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
        val proxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "ssh", "naive", "anytls", "shadowtls")
        require((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("type") in proxyTypes }) { "Configuration has no actual proxy node" }
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray()
        require((0 until inbounds.length()).any { inbounds.optJSONObject(it)?.optString("type") == "tun" }) { "Paired profiles require VPN/TUN routing" }
        val routes = config.optJSONObject("route")?.optJSONArray("rules") ?: JSONArray()
        val privateIndex = (0 until routes.length()).firstOrNull { index ->
            val rule = routes.optJSONObject(index)
            val cidrs = rule?.optJSONArray("ip_cidr") ?: JSONArray()
            val entries = (0 until cidrs.length()).map { cidrs.optString(it) }
            rule != null && rule.optString("outbound") in tags && "100.64.0.0/10" in entries && rule.optString("clash_mode").isBlank() && rule.optString("type") != "logical"
        }
        require(privateIndex != null) { "Configuration has no Tailscale private-network rule" }
        for (index in 0 until privateIndex) {
            val earlier = routes.getJSONObject(index)
            val action = earlier.optString("action", "route")
            val dnsOnly = earlier.optString("protocol") == "dns"
            require(action in setOf("sniff", "resolve") || (action == "hijack-dns" && dnsOnly)) { "A rule before the private-network rule would override Home traffic." }
        }
        val globalIndex = (0 until routes.length()).firstOrNull { routes.optJSONObject(it)?.optString("clash_mode").equals("Global", true) }
        require(globalIndex == null || privateIndex < globalIndex) { "Private-network rules must precede global proxy rules." }
    }
}
