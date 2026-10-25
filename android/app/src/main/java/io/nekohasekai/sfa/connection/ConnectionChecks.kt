package io.nekohasekai.sfa.connection

import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

/** Local, configuration-only evidence. None of these inputs may be logged or persisted. */
internal data class ConnectionCheckInput(
    val local: Boolean,
    val started: Boolean,
    val commandConnected: Boolean,
    val mode: ConnectionCheckMode,
    val ownership: VpnOwnership,
    val vpnNetwork: Long?,
    val underlyingNetwork: Long,
    val runtimeEpoch: Long?,
    val runtimeNetwork: Long?,
    val runtimeConfig: String?,
    val selections: Map<String, String>,
    val protection: VpnProtectionSnapshot,
    val selectedProfileMatches: Boolean = true,
)

internal enum class ConnectionCheckMode {
    Global, Rule, Direct, Unknown;
    companion object {
        fun from(value: String?): ConnectionCheckMode = when (value) {
            "Global" -> Global
            "Rule" -> Rule
            "Direct" -> Direct
            else -> Unknown
        }
    }
}

internal enum class VpnOwnership { Ours, Other, Unknown }
internal enum class ConnectionCheckLevel(val label: String) {
    Configured("Configured"), Attention("Attention"), Unknown("Unconfirmed"), Information("Details"),
}
internal enum class ConnectionCheckId(val title: String) {
    Vpn("Active VPN"), Mode("Proxy mode"), Capture("IPv4 / IPv6"), Dns("App DNS"),
    Exceptions("Direct exceptions"), Calling("Wi-Fi Calling"), AlwaysOn("Always-on VPN"), Lockdown("Block connections without VPN"),
}

/** The display boundary contains fixed text and safe counts, never configuration strings. */
internal data class ConnectionCheckItem(
    val id: ConnectionCheckId,
    val level: ConnectionCheckLevel,
    val detail: String,
    val count: Int? = null,
)
internal data class ConnectionCheckReport(val items: List<ConnectionCheckItem>)

/** Deliberately recognizes KOne's generated policy, rather than pretending to simulate sing-box. */
internal object ConnectionChecks {
    fun inspect(input: ConnectionCheckInput): ConnectionCheckReport {
        val unavailable = when {
            !input.local -> "Return to the local connection before checking."
            !input.selectedProfileMatches -> "The selection and active connection differ. Check again shortly."
            !input.started || input.runtimeConfig == null || (input.runtimeEpoch ?: 0L) <= 0L -> "Connect to a node first."
            !input.commandConnected || input.mode == ConnectionCheckMode.Unknown -> "The active mode is unconfirmed."
            input.ownership != VpnOwnership.Ours || input.vpnNetwork == null -> "Cannot confirm that the active VPN belongs to KOne."
            input.underlyingNetwork == 0L || input.runtimeNetwork != input.underlyingNetwork -> "The network is changing. Check again shortly."
            else -> null
        }
        if (unavailable != null) return unknown(unavailable)
        if (input.runtimeConfig!!.length > 2 * 1024 * 1024) return unknown("Cannot read the full active configuration.")
        return runCatching { inspectConfig(input, JSONObject(input.runtimeConfig!!)) }
            .getOrElse { unknown("Cannot read the full active configuration.") }
    }

    fun changed(): ConnectionCheckReport = unknown("The connection state changed. Check again.")

    private fun unknown(detail: String): ConnectionCheckReport = ConnectionCheckReport(
        ConnectionCheckId.entries.map { ConnectionCheckItem(it, ConnectionCheckLevel.Unknown, detail) },
    )

    private fun inspectConfig(input: ConnectionCheckInput, config: JSONObject): ConnectionCheckReport {
        val outbounds = tagged(objects(config.optJSONArray("outbounds")))
        val route = config.getJSONObject("route")
        val rules = objects(route.optJSONArray("rules"))
        val dns = config.getJSONObject("dns")
        val dnsRules = objects(dns.optJSONArray("rules"))
        val servers = tagged(objects(dns.optJSONArray("servers")))
        val globalRules = rules.withIndex().filter { it.value.optString("clash_mode") == "Global" }
        val global = globalRules.singleOrNull()?.takeIf { exact(it.value, setOf("clash_mode", "outbound")) && it.value.optString("outbound") == "proxy" }
        val directTags = outbounds.filterValues { it.optString("type") == "direct" }.keys
        val endpoints = tagged(objects(config.optJSONArray("endpoints")))
            .filterValues { it.optString("type") == "tailscale" }.keys
        val preceding = global?.let { rules.take(it.index) }
        val appRules = preceding.orEmpty().filter { it.optString("outbound") in directTags && isAppPredicate(it, route = true) }
        val packages = appRules.flatMap { strings(it.opt("package_name")) }.toSet()
        val allKnownDirect = appRules.any { it.has("package_name_regex") }
        val inverse = allKnownDirect || appRules.any { it.optString("type") == "logical" }
        val understoodRoutes = preceding != null && preceding.all { rule ->
            rule.optString("action") in setOf("sniff", "hijack-dns", "reject") ||
                (rule.optString("outbound") in directTags && isAppPredicate(rule, route = true)) ||
                (rule.optString("outbound") in directTags + endpoints && isPrivateRoute(rule))
        }
        val proxy = proxyState(outbounds, input.selections)
        val tun = objects(config.optJSONArray("inbounds")).filter { it.optString("type") == "tun" }
        val dualStack = tun.isNotEmpty() && tun.all { node ->
            val addresses = strings(node.opt("address"))
            node.optBoolean("auto_route") && node.optBoolean("strict_route") &&
                addresses.any { literalPrefix(it, false) } && addresses.any { literalPrefix(it, true) } &&
                node.keys().asSequence().none { key ->
                    key.startsWith("include_") || key.startsWith("exclude_") || key.startsWith("route_address") ||
                        key.startsWith("route_exclude_address") || key.startsWith("inet4_route_") || key.startsWith("inet6_route_")
                }
        }
        val homeDns = servers.filterValues { it.optString("type") == "tailscale" && it.optString("endpoint") in endpoints }.keys
        val dnsGlobal = dnsRules.withIndex().filter { it.value.optString("clash_mode") == "Global" }.singleOrNull()
            ?.takeIf { exact(it.value, setOf("clash_mode", "server")) && it.value.optString("server") == "proxy-dns" }
        val understoodDns = dnsGlobal != null && dnsRules.take(dnsGlobal.index).all { rule ->
            rule.optString("action") == "reject" ||
                (rule.optString("server") == "local-dns" && isAppPredicate(rule, route = false)) ||
                (rule.optString("server") in homeDns && isHomeDns(rule, homeDns))
        }
        val dnsApps = dnsGlobal?.let { dnsRules.take(it.index).filter { r -> r.optString("server") == "local-dns" && isAppPredicate(r, route = false) } }.orEmpty()
        val appDnsMatches = appRules.map { predicate(it, "outbound") }.toSet() == dnsApps.map { predicate(it, "server") }.toSet()
        val dnsProxy = servers["proxy-dns"]
        val proxyDnsSafe = dnsProxy?.optString("detour") == "proxy" &&
            dnsProxy.optString("type") in setOf("udp", "tcp", "tls", "https", "quic", "h3") &&
            resolverTag(route.opt("default_domain_resolver")) == "proxy-dns" &&
            rules.any { exact(it, setOf("protocol", "action")) && it.optString("protocol") == "dns" && it.optString("action") == "hijack-dns" }
        val localDnsSafe = servers["local-dns"]?.let { local ->
            val detour = local.optString("detour")
            detour.isBlank() || detour in directTags
        } == true
        val bootstrapSafe = proxy.visited.mapNotNull(outbounds::get).plus(listOfNotNull(dnsProxy)).all { node ->
            val host = node.optString("server")
            if (host.isBlank() || literalAddress(host)) true else {
                val resolver = resolverTag(node.opt("domain_resolver"))
                val server = resolver?.let(servers::get)
                resolver != null && resolver != "proxy-dns" && server != null &&
                    (server.optString("detour").isBlank() || server.optString("detour") in directTags)
            }
        }
        val dnsConfigured = understoodRoutes && understoodDns && appDnsMatches && proxyDnsSafe && localDnsSafe && bootstrapSafe && proxy.safe &&
            tun.isNotEmpty() && tun.none { it.optString("dns_mode") == "disabled" }
        val callingRoute = rules.any(::isCallingRoute)
        val callingDns = dnsRules.any(::isCallingDns)
        val items = mutableListOf(
            item(ConnectionCheckId.Vpn, ConnectionCheckLevel.Configured, "KOne VPN is running on this device for the current Android user only."),
            when (input.mode) {
                ConnectionCheckMode.Global -> if (understoodRoutes && proxy.safe) item(ConnectionCheckId.Mode, ConnectionCheckLevel.Configured, "Global mode: public traffic uses the proxy by default, except direct exceptions.")
                    else item(ConnectionCheckId.Mode, ConnectionCheckLevel.Unknown, "Global mode is active, but the complete route cannot be determined.")
                ConnectionCheckMode.Rule -> item(ConnectionCheckId.Mode, ConnectionCheckLevel.Attention, "Rule mode is active. Some traffic may connect directly according to the rules.")
                ConnectionCheckMode.Direct -> item(ConnectionCheckId.Mode, ConnectionCheckLevel.Attention, "Direct mode is active.")
                ConnectionCheckMode.Unknown -> item(ConnectionCheckId.Mode, ConnectionCheckLevel.Unknown, "The active mode is unconfirmed.")
            },
            item(ConnectionCheckId.Capture, if (dualStack) ConnectionCheckLevel.Configured else ConnectionCheckLevel.Attention,
                if (dualStack) "The active configuration has dual-stack TUN and strict automatic routing. Its actual exit is untested." else "The active configuration does not meet all dual-stack routing requirements."),
            when {
                input.mode != ConnectionCheckMode.Global -> item(ConnectionCheckId.Dns, ConnectionCheckLevel.Unknown, "Check this item in global mode.")
                dnsConfigured -> item(ConnectionCheckId.Dns, ConnectionCheckLevel.Configured, "App DNS uses the proxy. Direct apps, bootstrap resolution, and Home domains have separate purposes.")
                else -> item(ConnectionCheckId.Dns, ConnectionCheckLevel.Unknown, "The complete DNS route cannot be determined. Actual resolution is untested.")
            },
            when {
                !understoodRoutes -> item(ConnectionCheckId.Exceptions, ConnectionCheckLevel.Unknown, "The current rules contain exceptions that cannot be fully evaluated.")
                allKnownDirect -> item(ConnectionCheckId.Exceptions, ConnectionCheckLevel.Information, "The active rules allow recognized apps to connect directly. A total cannot be determined.")
                inverse -> item(ConnectionCheckId.Exceptions, ConnectionCheckLevel.Information, "Only selected apps use the proxy. Other recognized apps can connect directly; a total cannot be determined.")
                packages.isNotEmpty() -> ConnectionCheckItem(ConnectionCheckId.Exceptions, ConnectionCheckLevel.Information, "Explicit direct apps in the active configuration; LAN and Home private networks are separate.", packages.size)
                else -> item(ConnectionCheckId.Exceptions, ConnectionCheckLevel.Configured, "No explicit direct-app list is active. LAN and Home private networks are separate.")
            },
            when {
                !callingRoute || !callingDns -> item(ConnectionCheckId.Calling, ConnectionCheckLevel.Information, "Complete calling compatibility rules were not found. System calling routes and registration are unverified.")
                input.mode == ConnectionCheckMode.Direct -> item(ConnectionCheckId.Calling, ConnectionCheckLevel.Attention, "Direct mode takes priority. Calling rules cannot ensure proxy routing.")
                !proxy.safe || proxy.tcpOnly || !understoodRoutes || !understoodDns -> item(ConnectionCheckId.Calling, ConnectionCheckLevel.Unknown, "Calling rules exist, but UDP and routing conditions are unconfirmed. The system calling route is unverified.")
                packages.isNotEmpty() || inverse -> item(ConnectionCheckId.Calling, ConnectionCheckLevel.Information, "Calling compatibility is configured. Direct-app and rejection rules take priority. Actual UDP availability and system calling routes are unverified.")
                else -> item(ConnectionCheckId.Calling, ConnectionCheckLevel.Information, "Calling compatibility is configured. Rejection and private-network rules take priority. UDP availability, system calling routes, and registration are unverified.")
            },
        )
        items += protectionItem(ConnectionCheckId.AlwaysOn, input.protection.alwaysOn, input.protection.readable)
        items += protectionItem(ConnectionCheckId.Lockdown, input.protection.lockdown, input.protection.readable)
        return ConnectionCheckReport(items)
    }

    private fun protectionItem(id: ConnectionCheckId, value: Boolean?, readable: VpnProtectionReadability): ConnectionCheckItem = when {
        readable == VpnProtectionReadability.Unsupported -> item(id, ConnectionCheckLevel.Unknown, "This Android version cannot read this setting.")
        readable != VpnProtectionReadability.Available -> item(id, ConnectionCheckLevel.Unknown, "Cannot read the system setting. Check system VPN settings.")
        value == null -> item(id, ConnectionCheckLevel.Unknown, "Cannot read the system setting. Check system VPN settings.")
        value -> item(id, ConnectionCheckLevel.Configured, "The system setting is enabled. Disconnect, network change, and reboot behavior are untested.")
        else -> item(id, ConnectionCheckLevel.Attention, if (id == ConnectionCheckId.Lockdown) "The system setting is disabled. Normal network access may resume when the VPN stops." else "The system setting is disabled.")
    }

    private fun isAppPredicate(rule: JSONObject, route: Boolean): Boolean {
        val target = if (route) "outbound" else "server"
        if (exact(rule, setOf(target, "package_name"))) return strings(rule.opt("package_name")).let { it.isNotEmpty() && it.all(PACKAGE::matches) }
        // An empty include-only list becomes this known-package predicate, not an empty direct list.
        if (exact(rule, setOf(target, "package_name_regex"))) return strings(rule.opt("package_name_regex")) == listOf(".+")
        if (!exact(rule, setOf(target, "type", "mode", "rules")) || rule.optString("type") != "logical" || rule.optString("mode") != "and") return false
        val terms = objects(rule.optJSONArray("rules"))
        return terms.size == 2 && exact(terms[0], setOf("package_name_regex")) && strings(terms[0].opt("package_name_regex")) == listOf(".+") &&
            exact(terms[1], setOf("package_name", "invert")) && terms[1].optBoolean("invert") &&
            strings(terms[1].opt("package_name")).let { it.isNotEmpty() && it.all(PACKAGE::matches) }
    }
    private fun predicate(rule: JSONObject, target: String) = JSONObject(rule.toString()).apply { remove(target) }.toString()
    private fun isPrivateRoute(rule: JSONObject): Boolean = exact(rule, setOf("ip_cidr", "outbound")) &&
        strings(rule.opt("ip_cidr")).let { it.isNotEmpty() && it.all(::privatePrefix) }
    private fun isHomeDns(rule: JSONObject, tags: Set<String>): Boolean = when {
        exact(rule, setOf("preferred_by", "server")) -> strings(rule.opt("preferred_by")).let { it.isNotEmpty() && it.all(tags::contains) }
        exact(rule, setOf("domain_suffix", "server")) -> strings(rule.opt("domain_suffix")).let { it.isNotEmpty() && it.all { suffix -> suffix == "ts.net" || suffix.endsWith(".ts.net") } }
        else -> false
    }
    private fun isCallingRoute(rule: JSONObject) = exact(rule, setOf("network", "port", "outbound")) &&
        rule.optString("network") == "udp" && rule.optString("outbound") == "proxy" &&
        rule.optJSONArray("port")?.let { (0 until it.length()).map(it::optInt).toSet() == setOf(500, 4500) } == true
    private fun isCallingDns(rule: JSONObject): Boolean {
        if (!exact(rule, setOf("type", "mode", "rules", "server")) || rule.optString("type") != "logical" ||
            rule.optString("mode") != "or" || rule.optString("server") != "proxy-dns") return false
        val terms = objects(rule.optJSONArray("rules"))
        return terms.size == 2 && terms.any { exact(it, setOf("domain_suffix")) && strings(it.opt("domain_suffix")) == listOf("3gppnetwork.org") } &&
            terms.any { exact(it, setOf("domain_keyword")) && strings(it.opt("domain_keyword")) == listOf("epdg") }
    }

    private data class ProxyState(val safe: Boolean, val tcpOnly: Boolean, val visited: Set<String>)
    private fun proxyState(nodes: Map<String, JSONObject>, selections: Map<String, String>): ProxyState {
        val path = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun validate(tag: String): Boolean {
            if (tag in path || path.size > 32) return false
            if (tag in visited) return true
            val node = nodes[tag] ?: return false
            path += tag
            val type = node.optString("type")
            val safe = when (type) {
                "selector", "urltest" -> {
                    val choices = strings(node.opt("outbounds"))
                    choices.isNotEmpty() && choices.all(::validate)
                }
                in PROXIES -> node.optString("server").isNotBlank()
                else -> false
            }
            val detour = node.optString("detour")
            val valid = safe && (detour.isBlank() || validate(detour))
            path -= tag
            if (valid) visited += tag
            return valid
        }
        val graphSafe = validate("proxy")
        val activePath = mutableSetOf<String>()
        var tcpOnly = false
        fun followSelected(tag: String): Boolean {
            if (!activePath.add(tag) || activePath.size > 32) return false
            val node = nodes[tag] ?: return false
            val valid = when (node.optString("type")) {
                "selector", "urltest" -> {
                    val choice = selections[tag]
                    choice != null && choice in strings(node.opt("outbounds")) && followSelected(choice)
                }
                in PROXIES -> {
                    val networks = strings(node.opt("network")).flatMap { it.split(',', '\n').map(String::trim) }.filter(String::isNotEmpty)
                    if (networks.isNotEmpty() && "udp" !in networks) tcpOnly = true
                    // An absent restriction is not a successful UDP probe.
                    true
                }
                else -> false
            }
            val detour = node.optString("detour")
            val result = valid && (detour.isBlank() || followSelected(detour))
            activePath -= tag
            return result
        }
        return ProxyState(graphSafe && followSelected("proxy"), tcpOnly, visited)
    }

    private fun item(id: ConnectionCheckId, level: ConnectionCheckLevel, detail: String) = ConnectionCheckItem(id, level, detail)
    private fun objects(array: JSONArray?): List<JSONObject> = array?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun tagged(nodes: List<JSONObject>): Map<String, JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        nodes.forEach { node -> val tag = node.getString("tag"); require(tag.isNotBlank() && result.put(tag, node) == null) }
        return result
    }
    private fun exact(node: JSONObject, keys: Set<String>) = node.keys().asSequence().toSet() == keys
    private fun strings(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).map(value::getString)
        else -> error("invalid list")
    }
    private fun resolverTag(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.optString("server").takeIf(String::isNotBlank)
        else -> null
    }
    private fun literalAddress(value: String) = literalBytes(value) != null
    private fun literalBytes(value: String): ByteArray? {
        if (':' in value) {
            // Numeric characters and a colon prohibit hostnames before Java's literal parser.
            if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return null
            return runCatching { InetAddress.getByName(value).address }.getOrNull()?.takeIf { it.size == 16 }
        }
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || (it.toIntOrNull() ?: 256) !in 0..255 }) return null
        return parts.map { it.toInt().toByte() }.toByteArray()
    }
    private fun literalPrefix(value: String, ipv6: Boolean): Boolean {
        val address = value.substringBefore('/')
        val prefix = value.substringAfter('/', "").toIntOrNull() ?: return false
        return literalAddress(address) && (':' in address) == ipv6 && prefix in 1..(if (ipv6) 128 else 32)
    }
    private fun privatePrefix(value: String): Boolean {
        val address = value.substringBefore('/'); val bytes = literalBytes(address) ?: return false
        val width = value.substringAfter('/', "").toIntOrNull() ?: return false
        if (width !in 1..bytes.size * 8) return false
        return PRIVATE_RANGES.any { parent ->
            val parentBytes = literalBytes(parent.substringBefore('/'))!!
            val parentWidth = parent.substringAfter('/').toInt()
            bytes.size == parentBytes.size && width >= parentWidth && (0 until parentWidth).all { bit ->
                (bytes[bit / 8].toInt() and (0x80 ushr (bit % 8))) == (parentBytes[bit / 8].toInt() and (0x80 ushr (bit % 8)))
            }
        }
    }
    private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val PROXIES = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "ssh", "naive", "anytls", "shadowtls")
    private val PRIVATE_RANGES = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16", "127.0.0.0/8", "fc00::/7", "fe80::/10", "::1/128")
}
