package cc.txwd.kone.app

import cc.txwd.kone.core.KoneConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The managed source with the person's own nodes after its own outbounds: the imported ones,
 * then each subscription's. A tag the source already uses (or the template, or an earlier group)
 * moves aside, so the console's names stay as they are; a chain inside a group (`detour`) follows
 * its node's new tag. The service and the App both build from what comes out, so they agree.
 */
object Merge {
    /** The merged source, and the tags each group's nodes went in under, in order. */
    class Merged(val source: JsonObject, val groups: List<List<String>>)

    fun into(source: JsonObject, groups: List<List<JsonObject>>): Merged {
        if (groups.all { it.isEmpty() }) return Merged(source, groups.map { emptyList() })
        val own = (source["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val taken = HashSet<String>()
        own.forEach { outbound -> outbound.tag?.let(taken::add); outbound.text("detour")?.let(taken::add) }
        taken += KoneConfig.RESERVED
        val added = mutableListOf<JsonObject>()
        val tags = groups.map { group ->
            val renamed = HashMap<String, String>()
            val retagged = group.map { outbound ->
                val wanted = outbound.tag ?: "Node"
                val tag = unique(wanted, taken)
                taken += tag
                if (outbound.tag != null) renamed.putIfAbsent(wanted, tag)
                JsonObject(outbound + ("tag" to JsonPrimitive(tag)))
            }
            val chained = retagged.map { outbound ->
                val detour = outbound.text("detour") ?: return@map outbound
                JsonObject(outbound + ("detour" to JsonPrimitive(renamed[detour] ?: detour)))
            }
            added += chained
            chained.map { it.tag!! }
        }
        return Merged(JsonObject(source + ("outbounds" to JsonArray(own + added))), tags)
    }

    private fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var index = 2
        while ("$base $index" in taken) index++
        return "$base $index"
    }

    private val JsonObject.tag get() = text("tag")?.takeIf { it.isNotBlank() }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
