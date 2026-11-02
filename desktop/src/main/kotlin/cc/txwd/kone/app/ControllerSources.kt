package cc.txwd.kone.app

import cc.txwd.kone.core.KoneConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Classify the actual outbound graph, never a node's display label. */
object ControllerSources {
    data class Plan(val source: JsonObject, val managed: Boolean)

    fun choose(source: JsonObject, ownTags: Set<String>, selected: String?): Plan {
        val outbounds = (source["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val protected = outbounds.any { it.text("type") in KoneConfig.PROXY_TYPES && it.text("tag") !in ownTags }
        if (!protected) return Plan(source, false)
        if (selected == null || selected !in ownTags) return Plan(source, true)
        val local = localOnly(source, ownTags) ?: return Plan(source, true)
        val available = (local["outbounds"] as JsonArray).mapNotNull { (it as? JsonObject)?.text("tag") }.toSet()
        return if (selected in available) Plan(local, false) else Plan(source, true)
    }

    fun localOnly(source: JsonObject, ownTags: Set<String>): JsonObject? {
        val all = (source["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val byTag = all.mapNotNull { node -> node.text("tag")?.let { it to node } }.toMap()
        fun independent(tag: String, path: Set<String>): Boolean {
            if (path.size >= 8 || tag in path) return false
            val node = byTag[tag] ?: return false
            if (tag !in ownTags && node.text("type") != "direct") return false
            val detour = node.text("detour")?.takeIf { it.isNotBlank() } ?: return true
            return independent(detour, path + tag)
        }
        val kept = ownTags.filter { independent(it, emptySet()) }.toMutableSet()
        kept.toList().forEach { tag ->
            var next = byTag[tag]?.text("detour")
            repeat(8) { next?.let { kept += it; next = byTag[it]?.text("detour") } }
        }
        val nodes = all.filter { it.text("tag") in kept }
        if (nodes.none { it.text("type") in KoneConfig.PROXY_TYPES }) return null
        return JsonObject(mapOf("outbounds" to JsonArray(nodes)) + listOfNotNull(source["route"]?.let { "route" to it }).toMap())
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
