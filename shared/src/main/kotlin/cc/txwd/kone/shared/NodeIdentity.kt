package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * Which node an outbound is, whatever it is called. A subscription's tags are made from its
 * names in order ("Tokyo", "Tokyo 2"), so an update that renames, reorders or repeats nodes can
 * bring the same tag back on another server. The person chose a server, not a place in the list:
 * a choice, and anything kept per node, follows the node through an update ([follow],
 * [renamed]), and a node listed twice is used once ([distinct]).
 */
object NodeIdentity {
    /**
     * [outbound] as a node, in 64 lowercase hex digits: the SHA-256 of every field but its tag,
     * each object's keys in one order. Equal for the same node however it is written or named, in
     * either App. Opaque: the node's settings and credentials cannot be read from it. It is for
     * comparing, not publishing, as a guessed password could still be checked against it.
     */
    fun key(outbound: JsonObject): String = digest(KEY, sorted(JsonObject(outbound - "tag")).toString())

    /**
     * The server and account [outbound] reaches, as opaque as its [key], whatever its TLS,
     * transport or chain: the SHA-256 of one JSON array holding, in order, its protocol, address,
     * ports (an array of its one port, or of its port ranges sorted) and account fields. Each
     * field is a JSON string of its own, so no two different accounts share a form, whatever
     * characters they hold. Null without a server.
     */
    fun endpoint(outbound: JsonObject): String? {
        val server = outbound.text("server")?.trim()?.removeSurrounding("[", "]")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val ports = (outbound["server_port"] as? JsonPrimitive)?.contentOrNull?.let(::listOf)
            ?: (outbound["server_ports"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.sorted()
            ?: emptyList()
        val fields = listOf(JsonPrimitive(outbound.text("type").orEmpty().lowercase()), JsonPrimitive(server), JsonArray(ports.map(::JsonPrimitive))) +
            CREDENTIALS.map { JsonPrimitive(outbound.text(it).orEmpty()) }
        return digest(ENDPOINT, JsonArray(fields).toString())
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
     * node ([key]); else, when its server and account ([endpoint]) carry one node in [before] and
     * one in [after], each perhaps listed twice, that node under new settings; else the node under
     * the same tag, when the provider moved it to a new address and kept its name, unless that
     * node was another one of [before]. Among twins, the one under the same tag, else the first.
     * Null when the node is gone, or became several on its server and account none of which is
     * the same node, so the caller says so rather than move the person elsewhere unasked. A tag
     * [before] does not hold (Automatic, a country, the console's) is not a node of this list and
     * comes back as it is.
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
            if (telling(endpoint)) {
                val found = after.indices.filter { afterEndpoints[it] == endpoint }
                // A node split into several, none of them the same node, is not guessed at, not even by its name.
                if (found.mapTo(HashSet()) { afterKeys[it] }.size > 1) return null
                pick(found, tag)?.let { return it }
            }
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

    /**
     * The lowercase hex SHA-256 of [label], a NUL and [canonical]. The label names the form hashed
     * and its version, so a key and an endpoint never coincide and a form changed after release
     * takes a new label. It holds no NUL, so the first one ends it; the form after it is JSON,
     * which writes every character of a field unambiguously. Only MessageDigest and a table of
     * digits: both are there on the oldest Android KOne is built for (API 21), where neither
     * HexFormat nor Java 8's map calls can be counted on.
     */
    private fun digest(label: String, canonical: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$label\u0000$canonical".toByteArray(Charsets.UTF_8))
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = DIGITS[value ushr 4]
            hex[index * 2 + 1] = DIGITS[value and 0x0f]
        }
        return String(hex)
    }

    private const val KEY = "kone.node.key.v1"
    private const val ENDPOINT = "kone.node.endpoint.v1"
    private const val DIGITS = "0123456789abcdef"

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
