package cc.txwd.kone.app

import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.RunningNames
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.i18n.tr
import cc.txwd.kone.shared.NodeIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The node list's changes, as three questions answered apart.
 *
 * Which node the person chose. A subscription's update renames, reorders and drops nodes, and a
 * list that changes shifts the tags of the lists after it (Merge), so a tag can come back on
 * another server. The choice follows its node as NodeIdentity (the shared core) knows it, by its
 * server and account too; a node that can no longer be found gives way to Automatic, said in a line.
 *
 * What was measured of each node (delays, health, bandwidth). It stays only with a node whose
 * outbound is exactly the one measured, its chain included: anything else measured something else.
 *
 * Whether the running core still runs what is listed. Only when each node is exactly one it
 * started with, none missing and none added, and its groups hold the same nodes: the selector and
 * Automatic hold them all, the group of each country the rules go through those whose names say
 * it. Then a rename or a new order costs no connection, the core carrying on under the names it
 * started with ([running]). After any other change, a node gone included, it starts afresh with
 * the list: its Automatic would still hold the nodes it started with.
 *
 * What it needs of the rest of the App is [Host].
 */
class NodeContinuity(private val host: Host) {
    interface Host {
        /** The person's choice: a node's tag, Automatic or a country's Automatic; null before any. */
        val selected: String?
        /** The countries the person's rules go through, each with a group of its nodes in the core. */
        val through: Set<String>
        /** [tag] kept as the person's choice; the running core is not moved by it. */
        fun keep(tag: String)
        /** What was measured of the nodes carried to their tags now ([carry]: each tag now to its tag before); the rest let go. */
        fun carry(carry: Map<String, String>)
        /** The running core no longer runs what is listed: it starts afresh with the list, now or once it is up. */
        fun reload()
        /** A change of node KOne made itself, said in a line. */
        fun tell(message: String)
    }

    /** Nodes as the core is given them: [nodes] in order, and every outbound by tag, the relays of chains included. */
    class Listing(private val outbounds: List<JsonObject>, val nodes: List<SourceNode>) {
        private val byTag: Map<String, JsonObject> = outbounds.mapNotNull { outbound -> outbound.tag?.let { it to outbound } }.toMap()

        /** Each node's outbound, in order. */
        val members: List<JsonObject> = nodes.mapNotNull { byTag[it.tag] }

        /**
         * Each node's outbound as the core runs it, whatever it and its relays are called: equal for
         * two nodes exactly when their settings and their chains are. Opaque, as NodeIdentity's keys are.
         */
        val exact: Map<String, String> by lazy { nodes.mapNotNull { node -> exactKey(node.tag, 0)?.let { node.tag to it } }.toMap() }

        /** Whether this is [other] again: the same nodes, and every outbound the same, relays included. */
        fun sameAs(other: Listing) = nodes == other.nodes && outbounds == other.outbounds

        private fun exactKey(tag: String, depth: Int): String? {
            val outbound = byTag[tag] ?: return null
            val detour = outbound.text("detour") ?: return NodeIdentity.key(outbound)
            // What it goes through, by what that is rather than by what it is called.
            val through = if (depth < 8) exactKey(detour, depth + 1) else null
            return NodeIdentity.key(JsonObject(outbound + ("detour" to JsonPrimitive(through?.let { "node $it" } ?: "missing $detour"))))
        }

        companion object {
            val NONE = Listing(emptyList(), emptyList())

            /** The nodes of a configuration KoneConfig built, under the tags the core is given. */
            fun of(built: KoneConfig.Built?): Listing =
                if (built == null) NONE else Listing((built.config["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty(), built.nodes)
        }
    }

    private var listed = Listing.NONE

    /** The nodes the running core was started with; null while none runs. */
    private var core: Listing? = null

    /** The names the running core knows the listed nodes by, for its ClashApi; a node it does not run exactly is not among them. */
    @Volatile
    var running: RunningNames = RunningNames.SAME
        private set

    /** A core started with [nodes]. */
    fun started(nodes: Listing) {
        core = nodes
        running = names(pairs(nodes, listed))
    }

    /** The core stopped. */
    fun stopped() {
        core = null
        running = RunningNames.SAME
    }

    /** The list now. The first listing only sets the ground; one like the last changes nothing. */
    fun listed(now: Listing): Boolean {
        val before = listed
        if (now.sameAs(before)) return false
        listed = now
        if (before.nodes.isNotEmpty()) {
            host.carry(pairs(before, now).mapNotNull { (tag, was) -> was?.let { tag to it } }.toMap())
            follow(before, now)
        }
        core?.let { started ->
            val pairs = pairs(started, now)
            running = names(pairs)
            // With nothing listed there is nothing to start it with: it carries on as it is.
            if (!same(started, now, pairs) && now.nodes.isNotEmpty()) host.reload()
        }
        return true
    }

    /** Where a test's result belongs when it completes, only while that exact node is still listed. */
    fun measuring(tag: String): () -> String? {
        val before = listed
        return {
            when {
                tag !in before.exact -> null
                listed === before -> tag
                else -> pairs(before, listed).entries.firstOrNull { it.value == tag }?.key
            }
        }
    }

    /** The person's choice after the list went from [before] to [now]. */
    private fun follow(before: Listing, now: Listing) {
        // Automatic, a country's Automatic, or a tag KOne never listed: nothing to follow.
        val chosen = host.selected?.takeIf { tag -> tag != KoneConfig.AUTO_TAG && before.nodes.any { it.tag == tag } } ?: return
        val tag = NodeIdentity.follow(chosen, before.members, now.members)
        when {
            tag == chosen -> Unit
            tag != null && now.nodes.any { it.tag == tag } -> host.keep(tag)
            else -> gone(before.nodes.first { it.tag == chosen }.name, now)
        }
    }

    /** The chosen node, [name], cannot be found (gone, or not to be told apart): Automatic instead, or the one node left. */
    private fun gone(name: String, now: Listing) {
        val instead = (if (now.nodes.size >= 2) KoneConfig.AUTO_TAG else now.nodes.firstOrNull()?.tag) ?: return
        host.keep(instead)
        val words = if (instead == KoneConfig.AUTO_TAG) tr("Automatic") else now.nodes.first { it.tag == instead }.name
        host.tell(tr("{0} can no longer be found; using {1}", name, words))
    }

    /**
     * Whether [now] runs as [from] does: each node exactly one of [from] ([pairs]), none left over,
     * and the group of each country the rules go through holding the same nodes.
     */
    private fun same(from: Listing, now: Listing, pairs: Map<String, String?>): Boolean {
        if (from.nodes.size != now.nodes.size || pairs.values.any { it == null }) return false
        val through = host.through
        val countries = from.nodes.associate { it.tag to it.country }
        fun group(country: String?) = country?.takeIf { it in through }
        return now.nodes.all { node -> group(node.country) == group(countries[pairs[node.tag]]) }
    }

    companion object {
        /**
         * Each node of [to] with the node of [from] whose outbound is exactly its own (a node that
         * kept its tag and its outbound with itself first, twins in order), or null for none.
         */
        fun pairs(from: Listing, to: Listing): Map<String, String?> {
            val free = HashMap<String, ArrayDeque<String>>()
            for (node in from.nodes) from.exact[node.tag]?.let { free.getOrPut(it) { ArrayDeque() }.addLast(node.tag) }
            val paired = HashMap<String, String?>()
            for (node in to.nodes) {
                val key = to.exact[node.tag] ?: continue
                if (free[key]?.remove(node.tag) == true) paired[node.tag] = node.tag
            }
            for (node in to.nodes) if (node.tag !in paired) paired[node.tag] = to.exact[node.tag]?.let { free[it]?.removeFirstOrNull() }
            return to.nodes.associate { it.tag to paired[it.tag] }
        }

        /** The names the core of [pairs]' first side knows each listed node by, where they differ from its tag; null for one it does not run. */
        fun names(pairs: Map<String, String?>): RunningNames {
            val differ = pairs.filter { (tag, name) -> name != tag }
            return if (differ.isEmpty()) RunningNames.SAME else RunningNames(differ)
        }

        private val JsonObject.tag get() = text("tag")?.takeIf { it.isNotBlank() }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/** Only the entries [carry] keeps, each under its tag now ([carry]: each tag now to its tag before). */
internal fun <V> MutableMap<String, V>.carry(carry: Map<String, String>) {
    val kept = carry.mapNotNull { (now, before) -> if (containsKey(before)) now to getValue(before) else null }
    clear()
    kept.forEach { (tag, value) -> put(tag, value) }
}
