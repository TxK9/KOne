package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Tailscale endpoint added to generated profiles for access to approved private devices.
 * Public traffic remains on the selected proxy path. Subnets and the tailnet hostname are
 * configurable through [HomeSettings]; private and managed editions use separate defaults.
 */
object HomeLink {
    const val DNS_TAG = "home-dns"

    /** The addresses Tailscale gives devices; always part of Home. */
    val TAILNET_RANGES = listOf("100.64.0.0/10", "fd7a:115c:a1e0::/48")

    /** The phone keeps its Tailscale identity here whatever node it goes out through. */
    private const val STATE_DIRECTORY = "tailscale-selfuse"

    class Home(val enabled: Boolean, val subnets: List<String>, val hostname: String) {
        companion object {
            val OFF = Home(false, emptyList(), "")
        }
    }

    fun isKOneConfig(config: JSONObject): Boolean {
        val outbounds = config.optJSONArray("outbounds") ?: return false
        val tags = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
        val selector = tags.firstOrNull { it.optString("tag") == PairedProfile.PROXY_TAG && it.optString("type") == "selector" } ?: return false
        val members = selector.optJSONArray("outbounds") ?: return false
        return (0 until members.length()).any { members.optString(it) == ShareLinks.NODE_TAG } &&
            tags.any { it.optString("tag") == ShareLinks.NODE_TAG }
    }

    /** Whether the configuration has the Home endpoint. */
    fun hasHome(content: String): Boolean {
        val endpoints = runCatching { JSONObject(content).optJSONArray("endpoints") }.getOrNull() ?: return false
        return (0 until endpoints.length()).any { endpoints.optJSONObject(it)?.optString("tag") == PairedProfile.HOME_TAG }
    }

    /**
     * The configuration with Home as [home] says: the endpoint, the route that sends the tailnet
     * and the home networks to it, and the DNS that answers the tailnet's names. A configuration
     * not built here comes back unchanged.
     */
    fun apply(content: String, home: Home): String {
        val config = try { JSONObject(content) } catch (_: Exception) { return content }
        if (!isKOneConfig(config)) return content
        apply(config, home)
        return config.toString(2)
    }

    internal fun apply(config: JSONObject, home: Home) {
        val endpoints = config.optJSONArray("endpoints") ?: JSONArray()
        val existing = (0 until endpoints.length()).mapNotNull { endpoints.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == PairedProfile.HOME_TAG }
        val others = JSONArray()
        for (index in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(index)
            if (endpoint?.optString("tag") != PairedProfile.HOME_TAG) others.put(endpoints.get(index))
        }
        if (home.enabled) {
            // Fields added by hand to the endpoint (a control server, an auth key) are kept.
            val endpoint = existing ?: JSONObject().put("type", "tailscale").put("tag", PairedProfile.HOME_TAG)
                .put("state_directory", STATE_DIRECTORY).put("accept_routes", true)
                .put("system_interface", false).put("on_demand", false).put("detour", PairedProfile.PROXY_TAG)
            if (home.hostname.isNotBlank()) endpoint.put("hostname", home.hostname) else endpoint.remove("hostname")
            others.put(endpoint)
        }
        if (others.length() > 0) config.put("endpoints", others) else config.remove("endpoints")

        val route = config.optJSONObject("route") ?: JSONObject().also { config.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray()
        val kept = (0 until rules.length()).map { rules.get(it) }.filterNot { (it as? JSONObject)?.optString("outbound") == PairedProfile.HOME_TAG }
        val routed = JSONArray()
        // The home route goes right after sniffing and DNS hijacking, ahead of every mode.
        val at = kept.indexOfFirst { rule -> !leading(rule as? JSONObject) }.let { if (it < 0) kept.size else it }
        kept.take(at).forEach { routed.put(it) }
        if (home.enabled) {
            routed.put(JSONObject().put("ip_cidr", JSONArray(TAILNET_RANGES + home.subnets)).put("action", "route").put("outbound", PairedProfile.HOME_TAG))
        }
        kept.drop(at).forEach { routed.put(it) }
        route.put("rules", routed)

        val dns = config.optJSONObject("dns") ?: JSONObject().also { config.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray()
        val keptServers = JSONArray()
        for (index in 0 until servers.length()) {
            if (servers.optJSONObject(index)?.optString("tag") != DNS_TAG) keptServers.put(servers.get(index))
        }
        val dnsRules = dns.optJSONArray("rules") ?: JSONArray()
        val keptDnsRules = JSONArray()
        if (home.enabled) {
            keptServers.put(JSONObject().put("type", "tailscale").put("tag", DNS_TAG).put("endpoint", PairedProfile.HOME_TAG).put("accept_search_domain", true))
            // Device names, short names like "nas", and the domains the tailnet routes to its own DNS.
            keptDnsRules.put(JSONObject().put("preferred_by", JSONArray().put(DNS_TAG)).put("server", DNS_TAG))
            keptDnsRules.put(JSONObject().put("domain_suffix", JSONArray().put("ts.net")).put("server", DNS_TAG))
        }
        for (index in 0 until dnsRules.length()) {
            if (dnsRules.optJSONObject(index)?.optString("server") != DNS_TAG) keptDnsRules.put(dnsRules.get(index))
        }
        dns.put("servers", keptServers)
        dns.put("rules", keptDnsRules)
    }

    private fun leading(rule: JSONObject?): Boolean {
        if (rule == null) return false
        val action = rule.optString("action", "route")
        return action == "sniff" || action == "resolve" || (action == "hijack-dns" && rule.optString("protocol") == "dns")
    }

    /**
     * The networks typed into the settings, one prefix each: "192.168.1.0/24", or an address
     * alone for that one device. A host address inside a prefix is moved to the network start.
     * Throws [IllegalArgumentException] naming the first entry that is not a network.
     */
    fun parseSubnets(text: String): List<String> = text.split(Regex("[\\s,，;；、]+")).filter { it.isNotBlank() }.map { entry ->
        normalize(entry) ?: throw IllegalArgumentException("$entry is not a subnet. Example: 192.168.1.0/24")
    }.distinct()

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6 = Regex("^[0-9A-Fa-f:.]+$")

    private fun normalize(entry: String): String? {
        val address = entry.substringBefore("/")
        val isV4 = ipv4.matches(address) && address.split(".").all { it.toInt() in 0..255 }
        val isV6 = !isV4 && address.contains(':') && ipv6.matches(address)
        if (!isV4 && !isV6) return null
        // Only literals reach this point, so nothing is looked up.
        val bytes = runCatching { InetAddress.getByName(address).address }.getOrNull() ?: return null
        if (isV4 != (bytes.size == 4)) return null
        val width = bytes.size * 8
        val prefix = if (entry.contains('/')) entry.substringAfter("/").toIntOrNull() ?: return null else width
        if (prefix !in 1..width) return null
        for (bit in prefix until width) bytes[bit / 8] = (bytes[bit / 8].toInt() and (0x80 ushr (bit % 8)).inv()).toByte()
        val text = if (bytes.size == 4) (InetAddress.getByAddress(bytes) as Inet4Address).hostAddress else compressed(bytes)
        return "$text/$prefix"
    }

    /** An IPv6 address in its short form: fd00::/64 rather than fd00:0:0:0:0:0:0:0/64. */
    private fun compressed(bytes: ByteArray): String {
        val groups = (0 until 8).map { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
        var bestStart = -1
        var bestLength = 1
        var index = 0
        while (index < 8) {
            if (groups[index] != 0) { index++; continue }
            val start = index
            while (index < 8 && groups[index] == 0) index++
            if (index - start > bestLength) { bestStart = start; bestLength = index - start }
        }
        val hex = groups.map { Integer.toHexString(it) }
        if (bestStart < 0) return hex.joinToString(":")
        return hex.take(bestStart).joinToString(":") + "::" + hex.drop(bestStart + bestLength).joinToString(":")
    }

    /** A name a tailnet accepts: lowercase letters, digits and hyphens, up to 63 of them. */
    fun cleanHostname(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-{2,}"), "-").trim('-').take(63).trimEnd('-')

    /** The network list as the settings show it. */
    fun subnetsText(subnets: List<String>): String = subnets.joinToString("  ")
}
