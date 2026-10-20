package cc.txwd.kone.shared

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
 * the Windows service does (desktop/service/hosts.go), so the two build the same configuration.
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
            if (name.length > 253 || !Names.isSite(name)) continue
            val raw = entry.address.trim()
            if ('/' in raw) continue
            val address = Names.network(raw)?.substringBefore('/') ?: continue
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
        val entry = if (Names.network(first) != null && '/' !in first) Host(second, first) else Host(first, second)
        return clean(listOf(entry)).firstOrNull()?.let { flatten(listOf(it)).first() }
    }

    /** What [read] found: the entries, and how many lines were neither an entry nor a comment. */
    class Read(val hosts: List<Host>, val left: Int)

    /** A hosts file's own names for this machine, which mean nothing to a node and are passed over quietly. */
    private val LOCAL = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts")

    /**
     * Text as people paste it: lines typed "name address" (either way round), or a hosts file,
     * with its comments, several names to one address ("192.0.2.10 nas nas.example.org") and its
     * localhost lines, which are passed over. Each entry is checked as [parse] checks one.
     */
    fun read(text: String): Read {
        val found = ArrayList<Host>()
        var left = 0
        for (raw in text.lines()) {
            val parts = raw.substringBefore('#').trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val address = parts.first().takeIf { Names.network(it) != null && '/' !in it }
            val entries = when {
                address != null && parts.size >= 2 -> parts.drop(1).filter { it.lowercase() !in LOCAL }.map { "$it $address" }
                parts.size == 2 -> listOf(parts.joinToString(" "))
                else -> null
            }
            if (entries == null) {
                left++
                continue
            }
            if (entries.isEmpty()) continue // only names for this machine
            val parsed = entries.mapNotNull(::parse)
            if (parsed.isEmpty()) left++ else found += parsed
        }
        return Read(found.distinct(), left)
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
