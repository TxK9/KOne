package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which node an outbound is, whatever it is called. A subscription's tags are made from its
 * names in order ("Tokyo", "Tokyo 2"), so an update that renames, reorders or repeats nodes can
 * bring the same tag back on another server. The person chose a server, not a place in the list:
 * a choice, and anything kept per node, follows the node through an update ([follow],
 * [renamed]), and a node listed twice is used once ([distinct]).
 */
object NodeIdentity {
    /**
     * [outbound] as a node: every field but its tag, each object's keys in one order. Equal for
     * the same node however it is written or named. It holds the node's credentials: compare it,
     * never show or log it.
     */
    fun key(outbound: JsonObject): String = sorted(JsonObject(outbound - "tag")).toString()

    /**
     * The server and account [outbound] reaches: its protocol, address, port or port ranges, user
     * and password, whatever its TLS, transport or chain. Null without a server. Like [key], never
     * shown or logged.
     */
    internal fun endpoint(outbound: JsonObject): String? {
        val server = outbound.text("server")?.trim()?.removeSurrounding("[", "]")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = (outbound["server_port"] as? JsonPrimitive)?.contentOrNull
            ?: (outbound["server_ports"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.sorted()?.joinToString(",")
        val account = CREDENTIALS.map { outbound.text(it).orEmpty() }
        return (listOf(outbound.text("type").orEmpty().lowercase(), server, port.orEmpty()) + account).joinToString("\u0000")
    }

    /** The nodes kept, and for each tag left out, the tag of the twin kept in its place. */
    class Distinct(val nodes: List<JsonObject>, val twins: Map<String, String>)

    /**
     * Tagged [nodes] with each node once: a later twin of an earlier node (the same [key], so
     * differing only in name) is left out, and a chain through it goes through the twin kept.
     * Twins found only once their chains are joined go too. Run it after notices and the person's
     * words have left nodes out ([NodeFilter]), so a notice copied from a real node never stands in
     * for it. Untagged nodes are kept as they are.
     */
    fun distinct(nodes: List<JsonObject>): Distinct {
        var kept = nodes
        val twins = LinkedHashMap<String, String>()
        while (true) {
            val first = HashMap<String, String>()
            val gone = HashMap<String, String>()
            val next = kept.filter { node ->
                val tag = node.tag ?: return@filter true
                val key = key(node)
                val twin = first[key]
                if (twin == null) {
                    first[key] = tag
                    return@filter true
                }
                gone[tag] = twin
                false
            }
            if (gone.isEmpty()) return Distinct(kept, twins)
            // Kotlin's own map calls: the oldest Android edition this is built into predates Java 8's.
            for (entry in twins.entries) gone[entry.value]?.let(entry::setValue)
            twins.putAll(gone)
            kept = next.map { node ->
                val twin = node.text("detour")?.let(gone::get) ?: return@map node
                JsonObject(node + ("detour" to JsonPrimitive(twin)))
            }
        }
    }

    /**
     * Where the node tagged [tag] in [before] is in [after], the nodes an update gave: the same
     * node ([key]); else, when no other node of [before] shares its server and account, the node on
     * them under new settings; else the node under the same tag, when the provider moved it to a
     * new address and kept its name, unless that node was another one of [before]. Among several,
     * the one under the same tag, else the first. Null when the node is gone, so the caller says so
     * rather than move the person elsewhere unasked. A tag [before] does not hold (Automatic, a
     * country, the console's) is not a node of this list and comes back as it is.
     */
    fun follow(tag: String, before: List<JsonObject>, after: List<JsonObject>): String? {
        val old = before.firstOrNull { it.tag == tag } ?: return tag
        return Match(before, after).follow(old)
    }

    /**
     * Each node of [before] that [after] holds under another tag ([follow]): its old tag to its
     * new one, so what is kept per node (a choice, its delays, its history) moves with it. A node
     * that kept its tag, or is gone, is not listed.
     */
    fun renamed(before: List<JsonObject>, after: List<JsonObject>): Map<String, String> {
        val match = Match(before, after)
        val moves = LinkedHashMap<String, String>()
        before.forEach { node ->
            val tag = node.tag ?: return@forEach
            match.follow(node)?.takeIf { it != tag }?.let { moves.getOrPut(tag) { it } }
        }
        return moves
    }

    /** [before] read once, for following any number of its nodes into [after]. */
    private class Match(before: List<JsonObject>, private val after: List<JsonObject>) {
        private val tagsByKey = HashMap<String, MutableSet<String>>()
        private val keysByEndpoint = HashMap<String, MutableSet<String>>()
        private val tagsByEndpoint = HashMap<String, MutableSet<String>>()
        private val afterTags = after.map { it.tag }
        private val afterKeys = after.map(::key)
        private val afterEndpoints = after.map(::endpoint)

        init {
            before.forEach { node ->
                val tag = node.tag ?: return@forEach
                val key = key(node)
                tagsByKey.getOrPut(key, ::HashSet) += tag
                endpoint(node)?.let { endpoint ->
                    keysByEndpoint.getOrPut(endpoint, ::HashSet) += key
                    tagsByEndpoint.getOrPut(endpoint, ::HashSet) += tag
                }
            }
        }

        fun follow(old: JsonObject): String? {
            val tag = old.tag ?: return null
            val key = key(old)
            pick(after.indices.filter { afterKeys[it] == key }, tag)?.let { return it }
            val endpoint = endpoint(old)
            if (telling(endpoint)) pick(after.indices.filter { afterEndpoints[it] == endpoint }, tag)?.let { return it }
            val named = afterTags.indexOf(tag)
            return tag.takeIf { named >= 0 && was(named).all { it == tag } }
        }

        /** Whether [endpoint] is one node's of before (with its exact twins), so it says which node it is. */
        private fun telling(endpoint: String?): Boolean = endpoint != null && keysByEndpoint[endpoint]?.size == 1

        /** The tags of before that after's node [index] carries on. */
        private fun was(index: Int): Set<String> =
            tagsByKey[afterKeys[index]].orEmpty() + afterEndpoints[index]?.takeIf(::telling)?.let(tagsByEndpoint::get).orEmpty()

        private fun pick(found: List<Int>, tag: String): String? {
            val tagged = found.filter { afterTags[it] != null }
            return (tagged.firstOrNull { afterTags[it] == tag } ?: tagged.firstOrNull())?.let(afterTags::get)
        }
    }

    /** What says whose account a node is, across the protocols KOne carries. */
    private val CREDENTIALS = listOf("uuid", "username", "user", "password", "auth_str", "auth")

    private fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.keys.sorted().associateWith { sorted(value.getValue(it)) })
        is JsonArray -> JsonArray(value.map(::sorted))
        else -> value
    }

    private val JsonObject.tag get() = text("tag")?.takeIf { it.isNotBlank() }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
