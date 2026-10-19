package cc.txwd.kone.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The DNS servers a person can choose, as the service takes them (desktop/service/dns.go): the
 * remote one, asked through the node, and the direct one, asked straight out. Anything else is
 * refused, so the default ("") keeps the template's own servers.
 */
object Dns {
    /** The presets by name, with the address each asks. */
    val REMOTE = linkedMapOf("cloudflare" to "1.1.1.1", "google" to "8.8.8.8", "quad9" to "9.9.9.9")
    val DIRECT = linkedMapOf("alidns" to "223.5.5.5", "dnspod" to "119.29.29.29")

    private val PATH = Regex("""/[A-Za-z0-9/_.~-]{0,99}""")

    /** The remote choice as the template takes it: a preset's name, https://host:port/path, or "" for the default. */
    fun remoteChoice(choice: String): String {
        val text = choice.trim()
        if (text in REMOTE) return text
        val (host, port, path) = doh(text) ?: return ""
        return "https://${if (':' in host) "[$host]" else host}:$port$path"
    }

    /** The direct choice as the template takes it: a preset's name, an address, or "" for the default. */
    fun directChoice(choice: String): String {
        val text = choice.trim()
        if (text in DIRECT) return text
        return address(text) ?: ""
    }

    /** The server asked through the node; a DoH host given by name is looked up straight out. */
    fun remote(choice: String): JsonObject {
        val server = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "type" to JsonPrimitive("https"), "tag" to JsonPrimitive("proxy-dns"), "server" to JsonPrimitive("1.1.1.1"), "detour" to JsonPrimitive(KoneConfig.PROXY_TAG),
        )
        REMOTE[choice]?.let { address ->
            server["server"] = JsonPrimitive(address)
            return JsonObject(server)
        }
        val (host, port, path) = doh(choice) ?: return JsonObject(server)
        server["server"] = JsonPrimitive(host)
        if (port != 443) server["server_port"] = JsonPrimitive(port)
        if (path != "/dns-query") server["path"] = JsonPrimitive(path)
        if (address(host) == null) server["domain_resolver"] = JsonPrimitive("local-dns")
        return JsonObject(server)
    }

    /** The server asked straight out: a preset, or an address over plain DNS. */
    fun direct(choice: String): JsonObject {
        val server = DIRECT[choice] ?: address(choice) ?: "223.5.5.5"
        return JsonObject(mapOf("type" to JsonPrimitive("udp"), "tag" to JsonPrimitive("local-dns"), "server" to JsonPrimitive(server)))
    }

    /** IPv4 only, or IPv4 first with IPv6 allowed. */
    fun strategy(ipv6: Boolean) = if (ipv6) "prefer_ipv4" else "ipv4_only"

    /** https://host[:port][/path]: the host a plain address (IPv6 in brackets) or a domain name of two labels or more. */
    private fun doh(text: String): Triple<String, Int, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("https://")) return null
        val rest = trimmed.removePrefix("https://")
        if (rest.length > 200) return null
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/dns-query"
        if (!PATH.matches(path)) return null
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']').takeIf { it >= 0 } ?: return null
            val after = authority.substring(end + 1)
            val port = if (after.isEmpty()) 443 else {
                if (!after.startsWith(":")) return null
                UserRules.portNumber(after.substring(1)) ?: return null
            }
            val address = address(authority.substring(1, end))?.takeIf { ':' in it } ?: return null
            return Triple(address, port, path)
        }
        var host = authority
        var port = 443
        val colon = authority.indexOf(':')
        if (colon >= 0) {
            port = UserRules.portNumber(authority.substring(colon + 1)) ?: return null
            host = authority.substring(0, colon)
        }
        host = host.lowercase()
        address(host)?.takeIf { ':' !in it }?.let { return Triple(it, port, path) }
        if (host.length > 253 || !KoneConfig.isSite(host)) return null
        return Triple(host, port, path)
    }

    /** One IPv4 or IPv6 address written as rules write them, without a range. */
    internal fun address(text: String): String? {
        val lower = text.trim().lowercase()
        if ('/' in lower) return null
        return UserRules.network(lower)?.substringBefore('/')
    }
}
