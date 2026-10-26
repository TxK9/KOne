package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** One upstream sing-box service owns the public proxy and the private Tailscale endpoint. */
object PairedProfile {
    const val HOME_TAG = "home-tailnet"
    const val PROXY_TAG = "proxy"
    private fun obj(vararg values: Pair<String, Any>) = JSONObject().apply { values.forEach { put(it.first, it.second) } }
    private fun array(vararg values: Any) = JSONArray().apply { values.forEach { put(it) } }

    const val DEFAULT_NAME = "Proxy and Home"

    /** The link's display name (its fragment), so country grouping can classify the saved profile. */
    fun displayName(link: String): String {
        val fragment = link.trim().substringAfter('#', "")
        val decoded = try { URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { fragment }
        val name = decoded.filterNot { it.isISOControl() }.trim()
        return if (name.isEmpty()) DEFAULT_NAME else name.take(64).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    }

    fun fromVlessLink(link: String, rulesDirectory: String = "rules"): String {
        // The fragment is only a display name and is often shared unencoded.
        val uri = try { URI(link.trim().substringBefore('#')) } catch (_: Exception) { throw IllegalArgumentException("Invalid connection link") }
        require(uri.scheme.equals("vless", true)) { "Import a VLESS link; use a complete sing-box configuration for other protocols" }
        val (userInfo, host, port) = authority(uri)
        val uuid = try { UUID.fromString(userInfo).toString() } catch (_: Exception) { throw IllegalArgumentException("Invalid connection identifier") }
        val parameters = mutableMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.forEach {
            val pieces = it.split('=', limit = 2)
            val key = URLDecoder.decode(pieces[0], "UTF-8")
            require(!parameters.containsKey(key)) { "Connection contains duplicate parameters" }
            // Query values are not form-encoded: a literal '+' (for example in a Base64 key) stays '+'.
            parameters[key] = URLDecoder.decode(pieces.getOrElse(1) { "" }.replace("+", "%2B"), "UTF-8")
        }
        // Xray renamed the plain TCP transport to "raw"; both mean no transport layer.
        require(parameters["type"].orEmpty() in setOf("", "tcp", "raw")) { "Import this transport using a complete configuration" }
        require(parameters["headerType"].orEmpty() in setOf("", "none")) { "Import this transport using a complete configuration" }
        require(parameters["security"] in setOf("reality", "tls")) { "The paired configuration requires an encrypted VLESS connection" }
        val serverName = parameters["sni"]?.takeIf { it.isNotBlank() } ?: host
        val tls = obj("enabled" to true, "server_name" to serverName,
            "utls" to obj("enabled" to true, "fingerprint" to fingerprint(parameters["fp"])))
        if (parameters["security"] == "reality") {
            val publicKey = realityPublicKey(parameters["pbk"] ?: throw IllegalArgumentException("Reality public key is missing"))
            val shortId = parameters["sid"].orEmpty()
            require(shortId.length <= 16 && shortId.length % 2 == 0 && shortId.all { it in "0123456789abcdefABCDEF" }) { "Invalid Reality short ID format" }
            tls.put("reality", obj("enabled" to true, "public_key" to publicKey, "short_id" to shortId))
        }
        val node = obj("type" to "vless", "tag" to "node", "server" to host, "server_port" to port,
            "uuid" to uuid, "tls" to tls)
        parameters["flow"]?.takeIf { it.isNotBlank() }?.let {
            require(it == "xtls-rprx-vision") { "Unsupported VLESS flow: $it" }
            node.put("flow", it)
        }
        return build(node, rulesDirectory)
    }

    /**
     * java.net.URI keeps the brackets of an IPv6 literal in [URI.host], which sing-box expects bare,
     * and drops the whole server authority for hostnames it rejects, such as names containing '_'.
     */
    private fun authority(uri: URI): Triple<String?, String, Int> {
        val (userInfo, rawHost, rawPort) = if (uri.host != null) {
            Triple(uri.userInfo, uri.host, uri.port)
        } else {
            // URI only falls back to a registry authority for names, never for bracketed IPv6.
            val raw = uri.rawAuthority ?: throw IllegalArgumentException("Connection server is missing")
            val hostPort = raw.substringAfterLast('@')
            val name = hostPort.substringBefore(':')
            require(name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._" }) { "Invalid connection server" }
            val portText = hostPort.substringAfter(':', "")
            val port = if (portText.isEmpty()) -1 else portText.toIntOrNull() ?: throw IllegalArgumentException("Invalid connection port")
            val userInfo = if ('@' in raw) URLDecoder.decode(raw.substringBeforeLast('@'), "UTF-8") else null
            Triple(userInfo, name, port)
        }
        val host = rawHost.removeSurrounding("[", "]").takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Connection server is missing")
        val port = if (rawPort == -1) 443 else rawPort
        require(port in 1..65535) { "Invalid connection port" }
        return Triple(userInfo, host, port)
    }

    // Names accepted by the core's uTLS client; Xray-only names have no equivalent there.
    private val fingerprints = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    private fun fingerprint(value: String?): String {
        val name = value?.trim()?.lowercase().orEmpty().ifEmpty { return "chrome" }
        require(name in fingerprints) { "Unsupported TLS fingerprint: $name" }
        return name
    }

    /** The core decodes public_key as unpadded URL-safe Base64, so normalize padded or standard-alphabet keys. */
    private fun realityPublicKey(value: String): String {
        val normalized = value.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        val decoded = try { Base64.getUrlDecoder().decode(normalized) } catch (_: Exception) { ByteArray(0) }
        require(decoded.size == 32) { "Invalid Reality public key format" }
        return normalized
    }

    fun build(node: JSONObject, rulesDirectory: String = "rules"): String {
        require(node.optString("type") != "direct" && node.optString("type") != "block") { "An actual proxy node is required." }
        val home = obj("type" to "tailscale", "tag" to HOME_TAG, "state_directory" to "tailscale-selfuse",
            "accept_routes" to true, "hostname" to "txwd-phone", "system_interface" to false, "on_demand" to false, "detour" to PROXY_TAG)
        val privateRoute = obj("ip_cidr" to array("100.64.0.0/10", "fd7a:115c:a1e0::/48", "192.168.10.0/24"), "action" to "route", "outbound" to HOME_TAG)
        val routes = array(
            obj("action" to "sniff"),
            obj("protocol" to "dns", "action" to "hijack-dns"),
            privateRoute,
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
                    obj("type" to "tailscale", "tag" to "home-dns", "endpoint" to HOME_TAG, "accept_search_domain" to true),
                ),
                "rules" to array(
                    obj("domain_suffix" to array("ts.net"), "server" to "home-dns"),
                    obj("clash_mode" to "Direct", "server" to "local-dns"),
                    obj("clash_mode" to "Global", "server" to "proxy-dns"),
                    obj("rule_set" to array("geosite-cn"), "server" to "local-dns"),
                ), "final" to "proxy-dns", "strategy" to "ipv4_only",
            ),
            "inbounds" to array(obj("type" to "tun", "tag" to "tun-in", "address" to array("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                "auto_route" to true)),
            "endpoints" to array(home),
            "outbounds" to array(obj("type" to "selector", "tag" to PROXY_TAG, "outbounds" to array("node"), "default" to "node"), node, obj("type" to "direct", "tag" to "direct")),
            "route" to obj("rules" to routes, "rule_set" to rulesets, "auto_detect_interface" to true,
                "default_domain_resolver" to "local-dns", "final" to PROXY_TAG),
            "experimental" to obj("cache_file" to obj("enabled" to true), "clash_api" to obj("default_mode" to "Rule")),
        )
        val content = config.toString(2)
        requirePaired(content)
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
            val earlier = routes.optJSONObject(index) ?: throw IllegalArgumentException("A rule before the private-network rule would override Home traffic.")
            val action = earlier.optString("action", "route")
            val dnsOnly = earlier.optString("protocol") == "dns"
            require(action in setOf("sniff", "resolve") || (action == "hijack-dns" && dnsOnly)) { "A rule before the private-network rule would override Home traffic." }
        }
        val globalIndex = (0 until routes.length()).firstOrNull { routes.optJSONObject(it)?.optString("clash_mode").equals("Global", true) }
        require(globalIndex == null || privateIndex < globalIndex) { "Private-network rules must precede global proxy rules." }
    }
}
