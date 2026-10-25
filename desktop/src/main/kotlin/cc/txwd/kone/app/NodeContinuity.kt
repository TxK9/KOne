package cc.txwd.kone.app

import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.RunningNames
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.shared.NodeIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The person's chosen node kept through changes of the node list. A subscription's update renames,
 * reorders and drops nodes, and a list that changes shifts the tags of the lists after it (Merge),
 * so a tag can come back on another server: the person chose a server, not a place in the list.
 * Each listing is compared with the last by NodeIdentity (the shared core): the choice follows its
 * node to the tag it has now; what is kept per node moves with it; and when the node is gone,
 * Automatic takes its place, said in a line. A running core knows the nodes by the tags they had
 * when it started: it carries on under them ([running]) rather than starting afresh, so a rename
 * or a new order costs no connection. What it needs of the rest of the App is [Host].
 */
class NodeContinuity(private val host: Host) {
    interface Host {
        /** The person's choice: a node's tag, Automatic or a country's Automatic; null before any. */
        val selected: String?
        /** The chosen node, listed as [tag] now, kept as the choice; a running core is on it already. */
        fun keep(tag: String)
        /** [tag] chosen in the person's place, as they would choose it: a running core moves to it. */
        fun choose(tag: String)
        /** What is kept per node (delays, health, bandwidth) moved from each old tag to its new one. */
        fun move(moves: Map<String, String>)
        /** A change of node KOne made itself, said in a line. */
        fun tell(message: String)
    }

    /** Every node's outbound as last listed, under its tag, and each node's name. */
    private var listed: List<JsonObject> = emptyList()
    private var names: Map<String, String> = emptyMap()

    /** The nodes the running core was started with; null while none runs. */
    private var core: List<JsonObject>? = null

    /** The names the running core knows the listed nodes by, for its ClashApi. */
    @Volatile
    var running: RunningNames = RunningNames.SAME
        private set

    /** A core started with [nodes], every node's outbound under its tag. */
    fun started(nodes: List<JsonObject>) {
        core = nodes
        running = names(nodes, listed)
    }

    /** The core stopped. */
    fun stopped() {
        core = null
        running = RunningNames.SAME
    }

    /**
     * The list now: [outbounds], every node's under its tag, and [nodes], as listed. The first
     * listing only sets the ground; a listing like the last changes nothing.
     */
    fun listed(outbounds: List<JsonObject>, nodes: List<SourceNode>) {
        val before = listed
        val named = names
        listed = outbounds
        names = nodes.associate { it.tag to it.name }
        if (outbounds == before) return
        core?.let { running = names(it, outbounds) }
        if (before.isEmpty()) return
        val moves = NodeIdentity.renamed(before, outbounds)
        if (moves.isNotEmpty()) host.move(moves)
        // Automatic, a country's Automatic, or a tag KOne never listed: nothing to follow.
        val chosen = host.selected?.takeIf { it != KoneConfig.AUTO_TAG && it in named } ?: return
        val now = NodeIdentity.follow(chosen, before, outbounds)
        when {
            now == chosen -> Unit
            now != null && now in names -> host.keep(now)
            else -> gone(named.getValue(chosen), nodes)
        }
    }

    /** The chosen node, [name], cannot be found (gone, or not to be told apart): Automatic instead, or the one node left. */
    private fun gone(name: String, nodes: List<SourceNode>) {
        val instead = if (nodes.size >= 2) KoneConfig.AUTO_TAG else nodes.firstOrNull()?.tag ?: return
        host.choose(instead)
        host.tell("$name can no longer be found; using ${if (instead == KoneConfig.AUTO_TAG) "Automatic" else names.getValue(instead)}")
    }

    companion object {
        /**
         * The names a core started with [core] knows the nodes of [listed] by, where they differ
         * from their tags now: a node that moved keeps its old name there, and a node new to the
         * list, or one whose tag another node held when the core started, is not carried by it.
         */
        fun names(core: List<JsonObject>, listed: List<JsonObject>): RunningNames {
            if (core == listed) return RunningNames.SAME
            val moves = NodeIdentity.renamed(core, listed)
            val back = moves.entries.associate { (old, new) -> new to old }
            val started = core.mapNotNullTo(HashSet()) { it.tag }
            val differ = HashMap<String, String?>()
            for (tag in listed.mapNotNull { it.tag }) {
                val name = back[tag] ?: tag.takeIf { it in started && it !in moves }
                if (name != tag) differ[tag] = name
            }
            return if (differ.isEmpty()) RunningNames.SAME else RunningNames(differ)
        }

        private val JsonObject.tag get() = (this["tag"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/**
 * Each entry moved from its old key to its new one ([moves]) at once, so two keys trading places
 * swap their values; an entry under a new key belonged to a node that is gone, and goes.
 */
internal fun <V> MutableMap<String, V>.rekey(moves: Map<String, String>) {
    val moving = moves.keys.filter { it in this }.associateWith { getValue(it) }
    moves.keys.forEach { remove(it) }
    moves.values.forEach { remove(it) }
    moving.forEach { (old, value) -> put(moves.getValue(old), value) }
}
