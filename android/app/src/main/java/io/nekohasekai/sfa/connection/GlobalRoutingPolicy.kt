package io.nekohasekai.sfa.connection

import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

/** Runtime-only policy. Android captures every app; explicit app exceptions are routed by the core. */
internal object GlobalRoutingPolicy {
    fun apply(
        content: String,
        strict: Boolean = true,
        directPackages: Set<String> = emptySet(),
        proxyPackages: Set<String>? = null,
    ): String {
        if (!strict) return content
        val config = JSONObject(content)
        val outbounds = objects(config.optJSONArray("outbounds"))
        val outboundByTag = tagged(outbounds, "Proxy outbound")
        val reachable = validateProxy(outboundByTag)
        val dns = config.optJSONObject("dns") ?: error("Global proxy requires DNS settings")
        val servers = tagged(objects(dns.optJSONArray("servers")), "DNS server")
        val proxyDns = servers["proxy-dns"] ?: error("Global proxy has no proxy DNS server")
        require(proxyDns.optString("detour") == "proxy") { "Proxy DNS must use a proxy outbound" }
        require(proxyDns.optString("type") in setOf("udp", "tcp", "tls", "https", "quic", "h3")) {
            "Global proxy requires a remote DNS server"
        }
        val route = config.optJSONObject("route") ?: error("Global proxy requires routing settings")
        val sourceRoutes = objects(route.optJSONArray("rules"))
        val sourceDnsRules = objects(dns.optJSONArray("rules"))
        val tuns = objects(config.optJSONArray("inbounds")).filter { it.optString("type") == "tun" }
        require(tuns.isNotEmpty()) { "Global proxy requires VPN/TUN routing" }

        val exempt = directPackages.toMutableSet()
        var whitelist: Set<String>? = proxyPackages
        for (tun in tuns) {
            exempt.addAll(strings(tun.opt("exclude_package")))
            if (tun.has("include_package")) {
                val included = strings(tun.opt("include_package")).toSet()
                whitelist = whitelist?.plus(included) ?: included
            }
            normalizeTun(tun)
        }
        require((exempt + whitelist.orEmpty()).all { PACKAGE.matches(it) }) { "Invalid per-app package name" }
        val appPredicates = mutableListOf<JSONObject>()
        if (exempt.isNotEmpty()) appPredicates += JSONObject().put("package_name", JSONArray(exempt.sorted()))
        whitelist?.let { included ->
            // Inverting package_name alone also matches an unknown UID. Require a known package first.
            val known = JSONObject().put("package_name_regex", JSONArray().put(".+"))
            appPredicates += if (included.isEmpty()) known else JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(known).put(JSONObject().put("package_name", JSONArray(included.sorted())).put("invert", true)))
        }
        if (appPredicates.isNotEmpty()) {
            require(servers.containsKey("local-dns")) { "Per-app direct routing requires local DNS" }
            route.put("find_process", true)
        }
        val directTag = outbounds.firstOrNull { it.optString("type") == "direct" }?.getString("tag")
            ?: "kone-direct".also { tag ->
                require(!outboundByTag.containsKey(tag)) { "Direct outbound name conflict" }
                config.getJSONArray("outbounds").put(JSONObject().put("type", "direct").put("tag", tag))
            }
        // FQDN connections sent to a direct exception must keep working when proxy DNS fails.
        val directOutbounds = objects(config.getJSONArray("outbounds")).filter { it.optString("type") == "direct" }
        require(servers.containsKey("local-dns")) { "Direct exceptions require separate DNS" }
        require(servers.getValue("local-dns").optString("detour") != "proxy") { "Direct DNS cannot depend on the proxy" }
        directOutbounds.forEach { it.put("domain_resolver", "local-dns") }

        // A named node needs an independent bootstrap resolver; ordinary application lookups do not use it.
        val originalResolver = resolverTag(route.opt("default_domain_resolver"))
        fun bootstrapFor(target: JSONObject) {
            val host = target.optString("server")
            if (host.isBlank() || isLiteral(host)) return
            var resolver = resolverTag(target.opt("domain_resolver"))
            if (resolver == null) {
                resolver = originalResolver?.takeIf { it != "proxy-dns" } ?: "local-dns"
                target.put("domain_resolver", resolver)
            }
            require(resolver != "proxy-dns" && servers.containsKey(resolver)) { "Proxy hostnames require separate bootstrap DNS" }
            val bootstrapDetour = servers.getValue(resolver).optString("detour")
            require(bootstrapDetour.isBlank() || outboundByTag[bootstrapDetour]?.optString("type") == "direct") {
                "Proxy bootstrap DNS requires an independent local connection"
            }
        }
        reachable.forEach { tag -> bootstrapFor(outboundByTag.getValue(tag)) }
        bootstrapFor(proxyDns)
        route.put("default_domain_resolver", "proxy-dns")

        val endpointTags = objects(config.optJSONArray("endpoints"))
            .filter { it.optString("type") == "tailscale" }.map { it.optString("tag") }.toSet()
        val homeDnsTags = servers.values.filter { it.optString("type") == "tailscale" && it.optString("endpoint") in endpointTags }
            .map { it.getString("tag") }.toSet()
        val newRoutes = JSONArray().put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        sourceRoutes.filter { it.optString("action") == "reject" }.forEach { newRoutes.put(copy(it)) }
        val privateRules = mutableSetOf<String>()
        for (rule in sourceRoutes) {
            val tag = rule.optString("outbound")
            if (tag !in endpointTags && outboundByTag[tag]?.optString("type") != "direct") continue
            if (rule.optString("action", "route") != "route" || rule.optBoolean("invert")) continue
            val cidrs = if (rule.optBoolean("ip_is_private")) PRIVATE_RANGES else strings(rule.opt("ip_cidr")).filter(::isPrivatePrefix)
            if (cidrs.isEmpty()) continue
            val safe = JSONObject().put("ip_cidr", JSONArray(cidrs)).put("outbound", tag)
            if (privateRules.add(safe.toString())) newRoutes.put(safe)
        }
        appPredicates.forEach { newRoutes.put(copy(it).put("outbound", directTag)) }
        newRoutes.put(JSONObject().put("clash_mode", "Global").put("outbound", "proxy"))
        // Other modes keep their source semantics; Global has already matched every remaining flow.
        sourceRoutes.filterNot {
            it.optString("action") in setOf("sniff", "hijack-dns", "reject") || it.optString("clash_mode").equals("Global", true)
        }.forEach { newRoutes.put(copy(it)) }
        route.put("rules", newRoutes)
        if (!route.has("final")) route.put("final", "proxy")

        val newDnsRules = JSONArray()
        sourceDnsRules.filter { it.optString("action") == "reject" }.forEach { newDnsRules.put(copy(it)) }
        // The managed domain refusals are also enforced at DNS, even if an imported profile omitted them there.
        sourceRoutes.filter { it.optString("action") == "reject" && it.keys().asSequence().all { key -> key in setOf("action", "domain", "domain_suffix") } }
            .filter { it.has("domain") || it.has("domain_suffix") }.forEach { rule ->
                if (sourceDnsRules.none { it.toString() == rule.toString() }) newDnsRules.put(copy(rule))
            }
        sourceDnsRules.forEach { rule ->
            if (rule.optString("server") !in homeDnsTags || rule.optBoolean("invert")) return@forEach
            val preferred = strings(rule.opt("preferred_by")).filter { it in homeDnsTags }
            val suffixes = strings(rule.opt("domain_suffix")).filter { it == "ts.net" || it.endsWith(".ts.net") }
            if (preferred.isNotEmpty()) newDnsRules.put(JSONObject().put("preferred_by", JSONArray(preferred)).put("server", rule.getString("server")))
            if (suffixes.isNotEmpty()) newDnsRules.put(JSONObject().put("domain_suffix", JSONArray(suffixes)).put("server", rule.getString("server")))
        }
        appPredicates.forEach { newDnsRules.put(copy(it).put("server", "local-dns")) }
        newDnsRules.put(JSONObject().put("clash_mode", "Global").put("server", "proxy-dns"))
        sourceDnsRules.filterNot { it.optString("action") == "reject" || it.optString("clash_mode").equals("Global", true) }
            .forEach { newDnsRules.put(copy(it)) }
        dns.put("rules", newDnsRules)
        if (!dns.has("final")) dns.put("final", "proxy-dns")

        val usedSets = mutableSetOf<String>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                    if (key == "rule_set") usedSets.addAll(strings(value.opt(key))) else collect(value.opt(key))
                }
                is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it)) }
            }
        }
        collect(newRoutes); collect(newDnsRules)
        route.optJSONArray("rule_set")?.let { sets ->
            val kept = objects(sets).filter { it.optString("tag") in usedSets }
            if (kept.isEmpty()) route.remove("rule_set") else route.put("rule_set", JSONArray(kept))
        }
        return config.toString()
    }

    private fun validateProxy(outbounds: Map<String, JSONObject>): Set<String> {
        val visited = mutableSetOf<String>()
        val path = mutableSetOf<String>()
        fun visit(tag: String) {
            require(tag !in path) { "Proxy outbound dependency cycle" }
            if (tag in visited) return
            val node = outbounds[tag] ?: error("Referenced proxy outbound does not exist")
            val type = node.optString("type")
            require(type in PROXY_TYPES || type in setOf("selector", "urltest")) { "Proxy selection contains direct or unsupported outbounds" }
            path += tag
            if (type == "selector" || type == "urltest") {
                val choices = strings(node.opt("outbounds"))
                require(choices.isNotEmpty()) { "Proxy selection is empty" }
                if (node.has("default")) require(node.optString("default") in choices) { "Invalid default proxy selection" }
                choices.forEach(::visit)
                if (type == "selector") node.put("interrupt_exist_connections", true)
            } else require(node.optString("server").isNotBlank()) { "Proxy outbound has no server" }
            node.optString("detour").takeIf { it.isNotBlank() }?.let(::visit)
            path -= tag
            visited += tag
        }
        visit("proxy")
        return visited
    }

    private fun normalizeTun(tun: JSONObject) {
        val addresses = strings(tun.opt("address")).toMutableList()
        addresses.addAll(strings(tun.opt("inet4_address"))); addresses.addAll(strings(tun.opt("inet6_address")))
        require(addresses.all(::isLiteralPrefix)) { "Invalid TUN address" }
        if (addresses.none { !it.substringBefore('/').contains(':') }) addresses += "172.19.0.1/30"
        if (addresses.none { it.substringBefore('/').contains(':') }) addresses += "fdfe:dcba:9876::1/126"
        tun.keys().asSequence().toList().filter { key ->
            key.startsWith("include_") || key.startsWith("exclude_") || key.startsWith("route_address") || key.startsWith("route_exclude_address") ||
                key.startsWith("inet4_route_") || key.startsWith("inet6_route_") || key in setOf("inet4_address", "inet6_address")
        }.forEach(tun::remove)
        tun.put("address", JSONArray(addresses.distinct())).put("auto_route", true).put("strict_route", true)
        if (tun.optString("dns_mode") == "disabled") tun.put("dns_mode", "hijack")
    }

    private fun objects(array: JSONArray?): List<JSONObject> = array?.let { a ->
        (0 until a.length()).map { a.optJSONObject(it) ?: error("Configuration list must contain objects") }
    }.orEmpty()
    private fun tagged(values: List<JSONObject>, label: String): Map<String, JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        values.forEach { value ->
            val tag = value.optString("tag")
            require(tag.isNotBlank() && result.put(tag, value) == null) { "$label name is missing or duplicated" }
        }
        return result
    }
    private fun strings(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).map { value.opt(it) as? String ?: error("Configuration list must contain strings") }
        else -> error("Configuration field must be a string or list of strings")
    }
    private fun resolverTag(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> value.optString("server").takeIf { it.isNotBlank() }
        else -> null
    }
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun isLiteral(value: String): Boolean = literalBytes(value) != null
    private fun literalBytes(value: String): ByteArray? {
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return null
            return runCatching { InetAddress.getByName(value).address }.getOrNull()?.takeIf { it.size == 16 }
        }
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || (it.toIntOrNull() ?: 256) !in 0..255 }) return null
        return parts.map { it.toInt().toByte() }.toByteArray()
    }
    private fun isLiteralPrefix(value: String): Boolean {
        val bytes = literalBytes(value.substringBefore('/')) ?: return false
        val width = value.substringAfter('/', "").toIntOrNull() ?: return false
        return width in 1..bytes.size * 8
    }
    private fun isPrivatePrefix(value: String): Boolean {
        if (!isLiteralPrefix(value)) return false
        val bytes = literalBytes(value.substringBefore('/'))!!
        val prefix = value.substringAfter('/').toInt()
        return PRIVATE_RANGES.any { parent ->
            val parentBytes = literalBytes(parent.substringBefore('/'))!!
            val parentPrefix = parent.substringAfter('/').toInt()
            bytes.size == parentBytes.size && prefix >= parentPrefix && (0 until parentPrefix).all { bit ->
                (bytes[bit / 8].toInt() and (0x80 ushr (bit % 8))) == (parentBytes[bit / 8].toInt() and (0x80 ushr (bit % 8)))
            }
        }
    }

    private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val PROXY_TYPES = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "ssh", "naive", "anytls", "shadowtls")
    private val PRIVATE_RANGES = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16", "127.0.0.0/8", "fc00::/7", "fe80::/10", "::1/128")
}
