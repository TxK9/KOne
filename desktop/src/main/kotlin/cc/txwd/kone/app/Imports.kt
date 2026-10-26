package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr

import cc.txwd.kone.shared.SharedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The nodes the person imported (⋮ → Import from clipboard, Import from file), sealed like the console's in
 * imported.bin in the App's folder. They join the console's nodes and go through the same
 * template, so the Claude block holds for them too; without a key they are not used at all.
 */
class Imports(private val file: File, private val vault: Vault) {
    /** Each node's outbound, tagged with its name. */
    @Volatile
    var all: List<JsonObject> = load()
        private set

    /** Adds [found], each under its name (or Imported and a number), leaving out what is here already; how many were new. */
    fun add(found: List<SharedNode>): Int = addOutbounds(found.map { (Json.parseToJsonElement(it.outbound.toString()) as JsonObject) to it.name })

    /** Adds outbounds with their names, as [add] does; a tag they carry is set aside for the name. */
    @Synchronized
    fun addOutbounds(found: List<Pair<JsonObject, String?>>): Int {
        val kept = all.toMutableList()
        val keys = kept.mapTo(HashSet(), ::key)
        val tags = kept.mapNotNullTo(HashSet()) { it.tag }
        var added = 0
        for ((given, name) in found) {
            val outbound = JsonObject(given - "tag")
            if (!keys.add(key(outbound)) || kept.size >= MOST) continue
            val tag = unique(name ?: tr("Imported {0}" , kept.size + 1), tags)
            tags += tag
            kept += JsonObject(outbound + ("tag" to JsonPrimitive(tag)))
            added++
        }
        if (added > 0) save(kept)
        return added
    }

    /** Puts [outbound] in place of the node at [index], under [name] (unique among the rest); false when there is none, or it is another's twin. */
    @Synchronized
    fun replace(index: Int, outbound: JsonObject, name: String): Boolean {
        if (index !in all.indices) return false
        val others = all.filterIndexed { i, _ -> i != index }
        val clean = JsonObject(outbound - "tag")
        if (others.any { key(it) == key(clean) }) return false
        val tag = unique(name.ifBlank { tr("Imported {0}" , index + 1) }, others.mapNotNullTo(HashSet()) { it.tag })
        save(all.toMutableList().also { it[index] = JsonObject(clean + ("tag" to JsonPrimitive(tag))) })
        return true
    }

    @Synchronized
    fun remove(tag: String): Boolean {
        val kept = all.filterNot { it.tag == tag }
        if (kept.size == all.size) return false
        save(kept)
        return true
    }

    private fun load(): List<JsonObject> {
        if (!file.isFile) return emptyList()
        val plain = runCatching { vault.open(file.readBytes()) }.getOrNull() ?: return emptyList()
        return runCatching { (Json.parseToJsonElement(plain.decodeToString()) as JsonArray).mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())
    }

    private fun save(nodes: List<JsonObject>) {
        all = nodes
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(vault.seal(JsonArray(nodes).toString().toByteArray()))
        }
    }

    companion object {
        private const val MOST = 200

        private fun unique(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var index = 2
            while ("$base $index" in taken) index++
            return "$base $index"
        }

        /** The same node whatever its name or the order of its fields. */
        private fun key(outbound: JsonObject): String = sorted(JsonObject(outbound - "tag")).toString()

        private fun sorted(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.keys.sorted().associateWith { sorted(value.getValue(it)) })
            is JsonArray -> JsonArray(value.map(::sorted))
            else -> value
        }

        private val JsonObject.tag get() = text("tag")?.takeIf { it.isNotBlank() }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
