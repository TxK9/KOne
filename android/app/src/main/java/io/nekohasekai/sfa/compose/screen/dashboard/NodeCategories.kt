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

/** How a category's nodes are ordered: as saved, fastest first, or by name. */
internal enum class NodeSort {
    Saved,
    Latency,
    Name,
    ;

    fun next(): NodeSort = entries[(ordinal + 1) % entries.size]
}

/**
 * Ordering, searching and finding copies among one category's nodes. Pure over plain values,
 * so it is unit tested; the deck hands it profiles and their readings.
 */
internal object NodeListing {
    /** A place when fastest first: replies by their latency, then no reply, then unreachable, then not yet tested. */
    fun rank(reading: NodeReading?): Long = when {
        reading == null -> Long.MAX_VALUE
        reading.health == NodeHealth.Down -> Long.MAX_VALUE - 1
        reading.failed -> Long.MAX_VALUE - 2
        else -> reading.ms?.toLong() ?: (Long.MAX_VALUE - 3)
    }

    /** [items] in [sort]'s order; equal places keep their saved order. */
    fun <T> sort(items: List<T>, sort: NodeSort, name: (T) -> String, rank: (T) -> Long): List<T> = when (sort) {
        NodeSort.Saved -> items
        NodeSort.Latency -> items.map { it to rank(it) }.sortedBy { it.second }.map { it.first }
        NodeSort.Name -> items.map { it to sortKey(name(it)) }.sortedWith(compareBy(NaturalOrder) { it.second }).map { it.first }
    }

    /** A name without the flags and marks in front of it, folded, so "🇯🇵 Tokyo" sorts under T. */
    fun sortKey(name: String): String = fold(name).dropWhile { !it.isLetterOrDigit() }

    /** Text order, with runs of digits compared as numbers: "Node 2" before "Node 10". */
    val NaturalOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val si = i
                val sj = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return@Comparator na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return@Comparator c
            } else {
                if (a[i] != b[j]) return@Comparator a[i].compareTo(b[j])
                i++
                j++
            }
        }
        (a.length - i) - (b.length - j)
    }

    /**
     * Whether a node answers [query]: every word in it is in the name, or names the node's
     * country, so "japan" finds "Tokyo 01". Case and accents are ignored.
     */
    fun matches(name: String, flag: String?, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = fold(name)
        val country = Countries.byCode(flag)
        // The country's own names only: its cities would make "osaka" find every node in Japan.
        val countryWords = country?.let { listOf(it.code, it.en, it.zh) }.orEmpty().map(::fold)
        return words.all { word -> word in text || countryWords.any { word in it } }
    }

    /**
     * The copies to merge away: of nodes with the same [fingerprint], all but one are copies. The
     * one kept is a managed node, else the node in use, else the first. Managed nodes are never copies.
     */
    fun duplicates(ids: List<Long>, fingerprint: (Long) -> String?, inUse: Long, managed: (Long) -> Boolean): Set<Long> {
        val copies = LinkedHashSet<Long>()
        ids.groupBy(fingerprint).forEach { (print, same) ->
            if (print == null || same.size < 2) return@forEach
            val keep = same.firstOrNull(managed) ?: same.firstOrNull { it == inUse } ?: same.first()
            same.filterTo(copies) { it != keep && !managed(it) }
        }
        return copies
    }

    private fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase(java.util.Locale.ROOT)
            .replace(SPACES, " ")
            .trim()

    private val COMBINING = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
}
