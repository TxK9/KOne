package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * A rejected link or configuration. [reason] is stable so the UI can show a translated
 * message; the exception message stays English for logs and non-UI callers.
 */
class PairedProfileException(val reason: PairedProfile.Reason, val detail: String? = null) :
    IllegalArgumentException(if (detail == null) reason.message else "${reason.message}: $detail")

/** One upstream sing-box service owns the public proxy and the private Tailscale endpoint. */
object PairedProfile {
    enum class Reason(val message: String) {
        INVALID_LINK("Invalid connection link"),
        NOT_VLESS("Import a VLESS link; use a complete sing-box configuration for other protocols"),
        INVALID_IDENTIFIER("Invalid connection identifier"),
        DUPLICATE_PARAMETER("Connection contains duplicate parameters"),
        UNSUPPORTED_TRANSPORT("Import this transport using a complete configuration"),
        UNENCRYPTED("The paired configuration requires an encrypted VLESS connection"),
        MISSING_REALITY_KEY("Reality public key is missing"),
        INVALID_REALITY_KEY("Invalid Reality public key format"),
        INVALID_SHORT_ID("Invalid Reality short ID format"),
        UNSUPPORTED_FLOW("Unsupported VLESS flow"),
        UNSUPPORTED_FINGERPRINT("Unsupported TLS fingerprint"),
        MISSING_SERVER("Connection server is missing"),
        INVALID_SERVER("Invalid connection server"),
        INVALID_PORT("Invalid connection port"),
        NOT_A_PROXY("An actual proxy node is required."),
        INVALID_CONFIGURATION("Invalid configuration format"),
        TAILSCALE_MISSING("Tailscale is disabled in this profile. Create a paired Proxy and Home profile first."),
        NO_PROXY_NODE("Configuration has no actual proxy node"),
        TUN_REQUIRED("Paired profiles require VPN/TUN routing"),
        NO_PRIVATE_RULE("Configuration has no Tailscale private-network rule"),
        RULE_OVERRIDES_HOME("A rule before the private-network rule would override Home traffic."),
        PRIVATE_AFTER_GLOBAL("Private-network rules must precede global proxy rules."),
    }

    private const val DETAIL_LIMIT = 32

    private fun fail(reason: Reason, detail: String? = null): Nothing =
        // A detail echoes link text into the UI: keep it short and on one line.
        throw PairedProfileException(reason, detail?.filterNot { it.isISOControl() }?.take(DETAIL_LIMIT)
            ?.let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it })

    private fun ensure(condition: Boolean, reason: Reason, detail: String? = null) {
        if (!condition) fail(reason, detail)
    }

    /** Malformed percent escapes are a link error, not a JDK decoder message shown to the user. */
    private fun decode(value: String): String =
        try { URLDecoder.decode(value, "UTF-8") } catch (_: IllegalArgumentException) { fail(Reason.INVALID_LINK) }

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
        val uri = try { URI(link.trim().substringBefore('#')) } catch (_: Exception) { fail(Reason.INVALID_LINK) }
        ensure(uri.scheme.equals("vless", true), Reason.NOT_VLESS)
        val (userInfo, host, port) = authority(uri)
        val uuid = try { UUID.fromString(userInfo).toString() } catch (_: Exception) { fail(Reason.INVALID_IDENTIFIER) }
        val parameters = mutableMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.forEach {
            val pieces = it.split('=', limit = 2)
            val key = decode(pieces[0])
            ensure(!parameters.containsKey(key), Reason.DUPLICATE_PARAMETER)
            // Query values are not form-encoded: a literal '+' (for example in a Base64 key) stays '+'.
            parameters[key] = decode(pieces.getOrElse(1) { "" }.replace("+", "%2B"))
        }
        // Xray renamed the plain TCP transport to "raw"; both mean no transport layer.
        ensure(parameters["type"].orEmpty() in setOf("", "tcp", "raw"), Reason.UNSUPPORTED_TRANSPORT)
        ensure(parameters["headerType"].orEmpty() in setOf("", "none"), Reason.UNSUPPORTED_TRANSPORT)
        ensure(parameters["security"] in setOf("reality", "tls"), Reason.UNENCRYPTED)
        val serverName = parameters["sni"]?.takeIf { it.isNotBlank() } ?: host
        val tls = obj("enabled" to true, "server_name" to serverName,
            "utls" to obj("enabled" to true, "fingerprint" to fingerprint(parameters["fp"])))
        if (parameters["security"] == "reality") {
            val publicKey = realityPublicKey(parameters["pbk"] ?: fail(Reason.MISSING_REALITY_KEY))
            val shortId = parameters["sid"].orEmpty()
            val validShortId = shortId.length <= 16 && shortId.length % 2 == 0 && shortId.all { it in "0123456789abcdefABCDEF" }
            ensure(validShortId, Reason.INVALID_SHORT_ID)
            tls.put("reality", obj("enabled" to true, "public_key" to publicKey, "short_id" to shortId))
        }
        val node = obj("type" to "vless", "tag" to "node", "server" to host, "server_port" to port,
            "uuid" to uuid, "tls" to tls)
        parameters["flow"]?.takeIf { it.isNotBlank() }?.let {
            ensure(it == "xtls-rprx-vision", Reason.UNSUPPORTED_FLOW, it)
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
            val raw = uri.rawAuthority ?: fail(Reason.MISSING_SERVER)
            val hostPort = raw.substringAfterLast('@')
            val name = hostPort.substringBefore(':')
            val validName = name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._" }
            ensure(validName, Reason.INVALID_SERVER)
            val portText = hostPort.substringAfter(':', "")
            val port = if (portText.isEmpty()) -1 else portText.toIntOrNull() ?: fail(Reason.INVALID_PORT)
            val userInfo = if ('@' in raw) decode(raw.substringBeforeLast('@')) else null
            Triple(userInfo, name, port)
        }
        val host = rawHost.removeSurrounding("[", "]").takeIf { it.isNotBlank() } ?: fail(Reason.MISSING_SERVER)
        val port = if (rawPort == -1) 443 else rawPort
        ensure(port in 1..65535, Reason.INVALID_PORT)
        return Triple(userInfo, host, port)
    }

    // Names accepted by the core's uTLS client; Xray-only names have no equivalent there.
    private val fingerprints = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    private fun fingerprint(value: String?): String {
        val name = value?.trim()?.lowercase().orEmpty().ifEmpty { return "chrome" }
        ensure(name in fingerprints, Reason.UNSUPPORTED_FINGERPRINT, name)
        return name
    }

    /** The core decodes public_key as unpadded URL-safe Base64, so normalize padded or standard-alphabet keys. */
    private fun realityPublicKey(value: String): String {
        val normalized = value.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        val decoded = try { Base64.getUrlDecoder().decode(normalized) } catch (_: Exception) { ByteArray(0) }
        ensure(decoded.size == 32, Reason.INVALID_REALITY_KEY)
        return normalized
    }

    fun build(node: JSONObject, rulesDirectory: String = "rules"): String {
        ensure(node.optString("type") != "direct" && node.optString("type") != "block", Reason.NOT_A_PROXY)
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
        val config = try { JSONObject(content) } catch (_: Exception) { fail(Reason.INVALID_CONFIGURATION) }
        val endpoints = config.optJSONArray("endpoints") ?: JSONArray()
        val tags = (0 until endpoints.length()).mapNotNull { index ->
            endpoints.optJSONObject(index)?.takeIf { it.optString("type") == "tailscale" }?.optString("tag")
        }.filter { it.isNotBlank() }
        ensure(tags.isNotEmpty(), Reason.TAILSCALE_MISSING)
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
        val proxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "ssh", "naive", "anytls", "shadowtls")
        ensure((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("type") in proxyTypes }, Reason.NO_PROXY_NODE)
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray()
        ensure((0 until inbounds.length()).any { inbounds.optJSONObject(it)?.optString("type") == "tun" }, Reason.TUN_REQUIRED)
        val routes = config.optJSONObject("route")?.optJSONArray("rules") ?: JSONArray()
        val privateIndex = (0 until routes.length()).firstOrNull { index ->
            val rule = routes.optJSONObject(index)
            val cidrs = rule?.optJSONArray("ip_cidr") ?: JSONArray()
            val entries = (0 until cidrs.length()).map { cidrs.optString(it) }
            // An inverted rule matches everything except the tailnet, and only the route action delivers to the endpoint.
            rule != null && rule.optString("outbound") in tags && "100.64.0.0/10" in entries && rule.optString("clash_mode").isBlank() &&
                rule.optString("type") != "logical" && !rule.optBoolean("invert") && rule.optString("action", "route") == "route"
        } ?: fail(Reason.NO_PRIVATE_RULE)
                for (index in 0 until privateIndex) {
            val earlier = routes.optJSONObject(index) ?: fail(Reason.RULE_OVERRIDES_HOME)
            val action = earlier.optString("action", "route")
            val dnsOnly = earlier.optString("protocol") == "dns"
            ensure(action in setOf("sniff", "resolve") || (action == "hijack-dns" && dnsOnly), Reason.RULE_OVERRIDES_HOME)
        }
        val globalIndex = (0 until routes.length()).firstOrNull { routes.optJSONObject(it)?.optString("clash_mode").equals("Global", true) }
        ensure(globalIndex == null || privateIndex < globalIndex, Reason.PRIVATE_AFTER_GLOBAL)
    }
}
