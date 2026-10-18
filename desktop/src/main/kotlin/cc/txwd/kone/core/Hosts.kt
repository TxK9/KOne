package cc.txwd.kone.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One fixed answer the person set: a name and an address for it (Settings → Hosts). */
data class Host(val name: String, val address: String) {
    fun toJson() = buildJsonObject { put("name", name); put("address", address) }

    companion object {
        fun fromJson(json: JsonObject): Host? {
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            return Host(text("name") ?: return null, text("address") ?: return null)
        }
    }
}

/**
 * The person's fixed answers as the template uses them, checked and written one way exactly as
 * the service does (desktop/service/hosts.go), so the two build the same configuration.
 */
object Hosts {
    const val NAMES = 64
    const val ADDRESSES = 8

    /** A name and its addresses, IPv4 and IPv6 apart. */
    data class Group(val name: String, val v4: List<String>, val v6: List<String>)

    /** Names as sites are written, plain addresses without a range, each pair once, grouped by name in first-given order, bounded. */
    fun clean(entries: List<Host>): List<Group> {
        val groups = LinkedHashMap<String, Pair<MutableList<String>, MutableList<String>>>()
        val seen = HashSet<String>()
        for (entry in entries) {
            val name = entry.name.trim().lowercase()
            if (name.length > 253 || !KoneConfig.isSite(name)) continue
            val raw = entry.address.trim()
            if ('/' in raw) continue
            val address = UserRules.network(raw)?.substringBefore('/') ?: continue
            if ("$name $address" in seen) continue
            val group = groups[name] ?: run {
                if (groups.size == NAMES) return@run null
                (mutableListOf<String>() to mutableListOf<String>()).also { groups[name] = it }
            } ?: continue
            if (group.first.size + group.second.size == ADDRESSES) continue
            seen += "$name $address"
            if (':' in address) group.second += address else group.first += address
        }
        return groups.map { (name, lists) -> Group(name, lists.first, lists.second) }
    }

    /** The groups back as entries, as they are kept. */
    fun flatten(groups: List<Group>): List<Host> = groups.flatMap { group -> (group.v4 + group.v6).map { Host(group.name, it) } }

    /**
     * An entry as a person types it: "name address", or a hosts file's "address name"; null when
     * one of the two is not a name or not an address.
     */
    fun parse(line: String): Host? {
        val parts = line.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val (first, second) = parts
        val entry = if (UserRules.network(first) != null && '/' !in first) Host(second, first) else Host(first, second)
        return clean(listOf(entry)).firstOrNull()?.let { flatten(listOf(it)).first() }
    }

    /** Each name answered from its addresses, IPv4 and IPv6 apart; none of a kind answers that kind with nothing. */
    fun dnsRules(groups: List<Group>): List<JsonObject> = groups.flatMap { group ->
        listOf(Triple("A", "A", group.v4), Triple("AAAA", "AAAA", group.v6)).map { (query, record, addresses) ->
            buildJsonObject {
                put("domain", buildJsonArray { add(JsonPrimitive(group.name)) })
                put("query_type", buildJsonArray { add(JsonPrimitive(query)) })
                put("action", "predefined")
                if (addresses.isNotEmpty()) put("answer", JsonArray(addresses.map { JsonPrimitive("${group.name}. IN $record $it") }))
            }
        }
    }

    /** The route rule that resolves the names here before routing, so a node is never asked for them. */
    fun resolveRule(groups: List<Group>): JsonObject = buildJsonObject {
        put("domain", JsonArray(groups.map { JsonPrimitive(it.name) }))
        put("action", "resolve")
    }
}
