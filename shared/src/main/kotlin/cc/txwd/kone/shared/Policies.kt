package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * How a rule through the node or a country picks its node ([Rule.pick]), as Clash's proxy groups
 * do, written as the sing-box groups both Apps can run:
 * - "" (manual): through the node picked on the main screen; through a country, its fastest;
 * - [FASTEST]: the fastest of the nodes that answer, tested every few minutes;
 * - [FALLBACK]: the first in the list's order that answers, kept until it stops answering.
 *
 * sing-box has a selector and a URL test and no balancing group, so load balancing is not offered.
 * Groups are added only to what the service runs; saved nodes are never rewritten.
 */
object Policies {
    const val FASTEST = "fastest"
    const val FALLBACK = "fallback"

    /** The groups through every node, named as the Status tab shows them. */
    const val FASTEST_TAG = "Fastest"
    const val FALLBACK_TAG = "First that answers"

    const val TEST_URL = "https://www.gstatic.com/generate_204"

    /**
     * sing-box moves off the node a URL test uses only for one faster by more than the tolerance.
     * A test gives up after 15 s, so no node is ever 50 s faster: the first in order that answers
     * is taken and kept until its test fails, and then the first that answers again. It stays
     * below 65535 − 15000, so sing-box's 16-bit sum of delay and tolerance never wraps.
     */
    const val FALLBACK_TOLERANCE = 50_000

    /** The group a country's [through] tag ("Through Japan") becomes for [pick]: the first that answers has its own. */
    fun countryTag(through: String, pick: String): String = if (pick == FALLBACK) "$through · first that answers" else through

    /** The group through every node for [pick], or null for the node in use. */
    fun allTag(pick: String): String? = when (pick) {
        FASTEST -> FASTEST_TAG
        FALLBACK -> FALLBACK_TAG
        else -> null
    }

    /** A group of [members], in their order, that picks its node as [pick] says. */
    fun group(tag: String, members: List<String>, pick: String): JsonObject = buildJsonObject {
        put("type", "urltest")
        put("tag", tag)
        putJsonArray("outbounds") { members.forEach { add(it) } }
        put("url", TEST_URL)
        if (pick == FALLBACK) {
            put("interval", "2m")
            put("tolerance", FALLBACK_TOLERANCE)
        } else {
            put("interval", "5m")
            put("tolerance", 50)
        }
    }

    /** Whether [tag] is one of the groups written here, which an App removes before it writes them again. */
    fun isGroup(tag: String): Boolean = tag == FASTEST_TAG || tag == FALLBACK_TAG || tag.endsWith(" · first that answers")
}
