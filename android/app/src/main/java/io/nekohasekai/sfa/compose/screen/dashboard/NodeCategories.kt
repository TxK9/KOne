package io.nekohasekai.sfa.compose.screen.dashboard

import cc.txwd.kone.shared.Countries
import cc.txwd.kone.shared.Country
import cc.txwd.kone.shared.aliasRegex
import io.nekohasekai.sfa.database.Profile

/** A user-named group of nodes. [flag] is an optional country code for its icon and name recognition. */
internal data class NodeCategory(val id: String, val name: String, val flag: String? = null) {
    val country: Country? get() = Countries.byCode(flag) ?: Countries.byName(name)
}

internal data class NodeCategoryState(
    val categories: List<NodeCategory> = emptyList(),
    val fallbackName: String = NodeCategories.DEFAULT_FALLBACK_NAME,
    val fallbackFlag: String? = null,
    val assignments: Map<Long, String> = emptyMap(),
)

internal data class NodeGroup(val id: String, val name: String, val flag: String?, val fallback: Boolean, val profiles: List<Profile>)

/**
 * Local organisation only. Nobody starts with preset countries: the fallback
 * group collects every node no user category recognises, and a user category
 * recognises nodes by its own name and, when it has a flag, that country's names.
 */
internal object NodeCategories {
    const val FALLBACK_ID = "local"
    const val DEFAULT_FALLBACK_NAME = "Local nodes"
    const val MAX_NAME = 16

    fun sanitizeName(raw: String): String = raw.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(MAX_NAME)

    fun matches(category: NodeCategory, nodeName: String): Boolean = Recogniser(category).matches(nodeName)

    /** What one category recognises, worked out once for a whole list rather than again for every node. */
    private class Recogniser(val category: NodeCategory) {
        private val country = category.country
        private val literal = category.name.trim().takeIf { it.length >= 2 }?.let(::aliasRegex)

        fun matches(nodeName: String): Boolean =
            country?.matches(nodeName) == true || literal?.containsMatchIn(nodeName) == true
    }

    fun categoryFor(profile: Profile, state: NodeCategoryState): String = categoryFor(profile.id, profile.name, state)

    /** An explicit choice wins; otherwise exactly one recognising category, else the fallback. */
    fun categoryFor(profileId: Long, name: String, state: NodeCategoryState): String =
        categoryFor(profileId, name, state, state.categories.map(::Recogniser))

    private fun categoryFor(profileId: Long, name: String, state: NodeCategoryState, recognisers: List<Recogniser>): String {
        state.assignments[profileId]?.let { assigned ->
            if (assigned == FALLBACK_ID || state.categories.any { it.id == assigned }) return assigned
        }
        return recognisers.filter { it.matches(name) }.singleOrNull()?.category?.id ?: FALLBACK_ID
    }

    fun group(profiles: List<Profile>, state: NodeCategoryState): List<NodeGroup> {
        val recognisers = state.categories.map(::Recogniser)
        val byCategory = profiles.groupBy { categoryFor(it.id, it.name, state, recognisers) }
        val fallback = NodeGroup(FALLBACK_ID, state.fallbackName, state.fallbackFlag, true, byCategory[FALLBACK_ID].orEmpty())
        return listOf(fallback) + state.categories.map { NodeGroup(it.id, it.name, it.flag, false, byCategory[it.id].orEmpty()) }
    }

    // One category per line: "id<TAB>name<TAB>flag". Names never contain tabs or line breaks.
    fun encode(categories: List<NodeCategory>): String =
        categories.joinToString("\n") { "${it.id}\t${sanitizeName(it.name)}\t${it.flag.orEmpty()}" }

    fun decode(text: String?): List<NodeCategory> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val id = parts.getOrNull(0)?.trim().orEmpty()
        val name = sanitizeName(parts.getOrNull(1).orEmpty())
        if (id.isEmpty() || id == FALLBACK_ID || name.isEmpty()) {
            null
        } else {
            NodeCategory(id, name, parts.getOrNull(2)?.trim()?.uppercase()?.takeIf { Countries.byCode(it) != null })
        }
    }.distinctBy { it.id }.toList()

    /** Earlier builds stored "ZZ", a country code or "custom:name" per profile; keep only those manual choices. */
    fun migrate(old: Map<Long, String>): NodeCategoryState {
        val categories = LinkedHashMap<String, NodeCategory>()
        val assignments = LinkedHashMap<Long, String>()
        for ((profileId, key) in old) {
            if (key == "ZZ") {
                assignments[profileId] = FALLBACK_ID
                continue
            }
            val category = if (key.startsWith("custom:")) {
                sanitizeName(key.removePrefix("custom:")).takeIf { it.isNotEmpty() }?.let { NodeCategory("m-custom-$it", it) }
            } else {
                Countries.byCode(key)?.let { NodeCategory("m-${it.code}", it.label, it.code) }
            } ?: continue
            categories.getOrPut(category.id) { category }
            assignments[profileId] = category.id
        }
        return NodeCategoryState(categories = categories.values.toList(), assignments = assignments)
    }
}
