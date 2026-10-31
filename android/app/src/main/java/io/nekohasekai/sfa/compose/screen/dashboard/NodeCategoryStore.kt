package io.nekohasekai.sfa.compose.screen.dashboard

import cc.txwd.kone.shared.Countries
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.Application

/** Persists the user's categories; Compose readers recompose when [state] changes. */
internal object NodeCategoryStore {
    private const val PREFS = "knine_node_categories"
    private const val LEGACY_PREFS = "knine_profile_categories"
    private const val KEY_VERSION = "version"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_FALLBACK_NAME = "fallback_name"
    private const val KEY_FALLBACK_FLAG = "fallback_flag"
    private const val ASSIGNMENT_PREFIX = "a:"
    private const val KEY_SORT = "sort"

    private val prefs: SharedPreferences by lazy {
        Application.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val holder by lazy { mutableStateOf(load()) }

    val state: NodeCategoryState get() = holder.value

    // Kept apart from [state], so choosing an order never regroups the nodes.
    private val sortHolder by lazy {
        mutableStateOf(NodeSort.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: NodeSort.Saved)
    }

    /** How every category's nodes are ordered, kept across launches. */
    var sort: NodeSort
        get() = sortHolder.value
        set(value) {
            if (value == sortHolder.value) return
            sortHolder.value = value
            prefs.edit().putString(KEY_SORT, value.name).apply()
        }

    private fun load(): NodeCategoryState {
        if (!prefs.contains(KEY_VERSION)) {
            val legacy = Application.application.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).all
                .mapNotNull { (id, value) -> id.toLongOrNull()?.let { key -> (value as? String)?.let { key to it } } }
                .toMap()
            val migrated = NodeCategories.migrate(legacy)
            save(migrated)
            return migrated
        }
        val assignments = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(ASSIGNMENT_PREFIX)) return@mapNotNull null
            val profileId = key.removePrefix(ASSIGNMENT_PREFIX).toLongOrNull() ?: return@mapNotNull null
            (value as? String)?.let { profileId to it }
        }.toMap()
        return NodeCategoryState(
            categories = NodeCategories.decode(prefs.getString(KEY_CATEGORIES, null)),
            fallbackName = NodeCategories.sanitizeName(prefs.getString(KEY_FALLBACK_NAME, null).orEmpty())
                .ifEmpty { NodeCategories.DEFAULT_FALLBACK_NAME },
            fallbackFlag = prefs.getString(KEY_FALLBACK_FLAG, null)?.takeIf { Countries.byCode(it) != null },
            assignments = assignments,
        )
    }

    private fun save(next: NodeCategoryState) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(ASSIGNMENT_PREFIX) }.forEach(editor::remove)
        next.assignments.forEach { (profileId, categoryId) -> editor.putString("$ASSIGNMENT_PREFIX$profileId", categoryId) }
        editor.putInt(KEY_VERSION, 1)
            .putString(KEY_CATEGORIES, NodeCategories.encode(next.categories))
            .putString(KEY_FALLBACK_NAME, next.fallbackName)
            .putString(KEY_FALLBACK_FLAG, next.fallbackFlag.orEmpty())
            .apply()
    }

    private fun update(change: (NodeCategoryState) -> NodeCategoryState) {
        val next = change(holder.value)
        if (next == holder.value) return
        holder.value = next
        save(next)
    }

    private fun newId(): String = "c" + System.currentTimeMillis().toString(36) + (0..0xfff).random().toString(36)

    /** Returns the new category's id. */
    fun add(name: String, flag: String? = null): String {
        val id = newId()
        val clean = NodeCategories.sanitizeName(name).ifEmpty { "New category" }
        update { it.copy(categories = it.categories + NodeCategory(id, clean, flag?.takeIf { code -> Countries.byCode(code) != null })) }
        return id
    }

    fun rename(id: String, name: String) {
        val clean = NodeCategories.sanitizeName(name)
        if (clean.isEmpty()) return
        update { state ->
            if (id == NodeCategories.FALLBACK_ID) {
                state.copy(fallbackName = clean)
            } else {
                state.copy(categories = state.categories.map { if (it.id == id) it.copy(name = clean) else it })
            }
        }
    }

    fun setFlag(id: String, flag: String?) {
        val code = flag?.takeIf { Countries.byCode(it) != null }
        update { state ->
            if (id == NodeCategories.FALLBACK_ID) {
                state.copy(fallbackFlag = code)
            } else {
                state.copy(categories = state.categories.map { if (it.id == id) it.copy(flag = code) else it })
            }
        }
    }

    /** Nodes explicitly placed in a deleted category go back to the fallback. */
    fun delete(id: String) {
        if (id == NodeCategories.FALLBACK_ID) return
        update { state ->
            state.copy(
                categories = state.categories.filterNot { it.id == id },
                assignments = state.assignments.filterValues { it != id },
            )
        }
    }

    fun move(id: String, toIndex: Int) {
        update { state ->
            val list = state.categories.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            if (from < 0) return@update state
            val item = list.removeAt(from)
            list.add(toIndex.coerceIn(0, list.size), item)
            state.copy(categories = list)
        }
    }

    /** Also used after a rename so a new name cannot move the node elsewhere. */
    fun assign(profileId: Long, categoryId: String) {
        update { it.copy(assignments = it.assignments + (profileId to categoryId)) }
    }

    /** Several nodes moved to one category at once, in a single save. */
    fun assignAll(profileIds: Collection<Long>, categoryId: String) {
        update { it.copy(assignments = it.assignments + profileIds.associateWith { categoryId }) }
    }

    /**
     * A backup's categories added to the ones here: missing categories come in with their ids,
     * existing ones keep their name and place, and only [assignments] for nodes the restore just
     * added are taken. The fallback's name and flag are taken while they are still the defaults.
     */
    fun restore(categories: List<NodeCategory>, assignments: Map<Long, String>, fallbackName: String, fallbackFlag: String?) {
        update { state ->
            val known = state.categories.map { it.id }.toSet()
            val incoming = categories.filter { it.id !in known && it.id != NodeCategories.FALLBACK_ID }
                .map { it.copy(name = NodeCategories.sanitizeName(it.name).ifEmpty { "New category" }, flag = it.flag?.takeIf { code -> Countries.byCode(code) != null }) }
            val all = state.categories + incoming
            val valid = all.map { it.id }.toSet() + NodeCategories.FALLBACK_ID
            val untouched = state.fallbackName == NodeCategories.DEFAULT_FALLBACK_NAME && state.fallbackFlag == null
            state.copy(
                categories = all,
                assignments = state.assignments + assignments.filterValues { it in valid },
                fallbackName = if (untouched) NodeCategories.sanitizeName(fallbackName).ifEmpty { state.fallbackName } else state.fallbackName,
                fallbackFlag = if (untouched) fallbackFlag?.takeIf { Countries.byCode(it) != null } else state.fallbackFlag,
            )
        }
    }

    fun forget(profileId: Long) {
        update { it.copy(assignments = it.assignments - profileId) }
    }
}
