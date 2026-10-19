package cc.txwd.kone.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A DNS server of the person's own for the names under one domain (Settings → DNS), as an intranet's. */
data class NameServer(val domain: String, val server: String) {
    fun toJson() = buildJsonObject { put("domain", domain); put("server", server) }

    companion object {
        fun fromJson(json: JsonObject): NameServer? {
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            return NameServer(text("domain") ?: return null, text("server") ?: return null)
        }
    }
}

/**
 * The person's own servers as the template uses them, checked and written exactly as the service
 * does (desktop/service/nameservers.go), so the two build the same configuration.
 */
object NameServers {
    const val MOST = 16

    /** One server, the domains it answers, and the tag the template gives it. */
    data class Group(val tag: String, val server: String, val domains: List<String>)

    /** Domains as sites are written, servers as plain addresses, each domain once (the first wins), [MOST] at most, grouped by server. */
    fun clean(entries: List<NameServer>): List<Group> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        val seen = HashSet<String>()
        for (entry in entries) {
            val domain = entry.domain.trim().lowercase()
            if (domain.length > 253 || !KoneConfig.isSite(domain) || domain in seen) continue
            val server = Dns.address(entry.server) ?: continue
            if (seen.size == MOST) continue
            seen += domain
            groups.getOrPut(server) { mutableListOf() } += domain
        }
        return groups.entries.mapIndexed { index, (server, domains) -> Group("own-dns-${index + 1}", server, domains) }
    }

    /** The groups back as entries, as they are kept. */
    fun flatten(groups: List<Group>): List<NameServer> = groups.flatMap { group -> group.domains.map { NameServer(it, group.server) } }

    /** An entry as typed: "domain server", either way round; null when one is not a domain or the other not an address. */
    fun parse(line: String): NameServer? {
        val parts = line.trim().split(Regex("""[\s,=]+""")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val (first, second) = parts
        val entry = if (Dns.address(first) != null) NameServer(second, first) else NameServer(first, second)
        return flatten(clean(listOf(entry))).firstOrNull()
    }

    fun dnsRules(groups: List<Group>): List<JsonObject> = groups.map { group ->
        buildJsonObject {
            put("domain_suffix", JsonArray(group.domains.map(::JsonPrimitive)))
            put("server", group.tag)
        }
    }

    fun servers(groups: List<Group>): List<JsonObject> = groups.map { group ->
        buildJsonObject { put("type", "udp"); put("tag", group.tag); put("server", group.server) }
    }
}
