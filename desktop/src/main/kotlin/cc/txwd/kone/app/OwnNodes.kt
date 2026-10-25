package cc.txwd.kone.app

import cc.txwd.kone.node.NodeDraft
import cc.txwd.kone.node.NodeForm
import cc.txwd.kone.node.QrReader
import cc.txwd.kone.node.ShareLinkWriter
import cc.txwd.kone.shared.SubscriptionContent
import cc.txwd.kone.ui.NodeEditing
import cc.txwd.kone.ui.NodeQr
import cc.txwd.kone.ui.qrModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.image.BufferedImage

/**
 * The person's own nodes as the Nodes page handles them: imported from text, written by hand in
 * the form, removed, and written out again as links or a QR code. Only the imported and subscribed
 * nodes are ever written out; the console's are the owner's, given to this key only. What it needs
 * of the rest of the App is [Host].
 */
class OwnNodes(private val scope: CoroutineScope, private val imports: Imports, private val host: Host) {
    interface Host {
        /** The console's configuration, which the person's nodes join; null until it is sent. */
        val managed: JsonObject?
        /** The node the person picked. */
        val selected: String?
        /** The imported nodes' tags in the order [imports] keeps them. */
        val imported: List<String>
        /**
         * Every node the App runs with, the console's and the person's, and which are the person's
         * (imported or subscribed), read together from one merge: ownership is never judged on
         * an older list than the nodes it is judged for.
         */
        fun own(): Own
        fun nameOf(tag: String): String
        /** The node the running connection uses, if any. */
        fun inUse(): String?
        /** Whether sing-box takes [source]. */
        fun accepts(source: JsonObject): Boolean
        /** [nodes] that sing-box accepts beside [managed]. */
        fun accepted(managed: JsonObject, nodes: List<JsonObject>): List<JsonObject>
        /** The node lists changed; [gone], when given, is no longer here. */
        fun nodesChanged(gone: String? = null)
        fun restart()
        fun refresh()
        /** A line on the Nodes page; [error] in the cut ink. */
        fun tell(message: String, error: Boolean)
        /** How an import went, on the dashboard: softly when [good], else until dismissed. */
        fun imported(message: String, good: Boolean)
        /** A subscription's link KOne fetches, kept and fetched at once; null when it was, else why not ("already here"). */
        fun addSubscription(link: String): String?
    }

    /** The nodes as merged now, and the tags of those that are the person's to write out. */
    class Own(val outbounds: List<JsonObject>, val theirs: Set<String>)

    /** A node being written by hand on the Nodes page. */
    var editing: NodeEditing? = null
        private set

    /** A node shown as a QR code. */
    var qr: NodeQr? = null
        private set

    /**
     * The share links in [text] (from the clipboard or a file), each read as the phone reads it and
     * checked by sing-box, added to the person's own nodes. Says how it went on the dashboard.
     */
    fun importText(text: String) {
        val source = host.managed ?: return
        scope.launch {
            val (message, good) = withContext(Dispatchers.IO) {
                // Share links, a Clash profile or a sing-box configuration, as a subscription is read.
                val read = SubscriptionContent.read(text)
                // A chain names its next node by tag, and imported nodes are tagged anew: chains come by subscription.
                val single = read.nodes.filter { it["detour"] == null }.take(100)
                if (single.isEmpty()) {
                    return@withContext when {
                        read.nodes.isNotEmpty() -> "Chained nodes can be added as a subscription" to false
                        read.refused > 0 -> (read.refusal ?: "No usable nodes") to false
                        else -> "No nodes found" to false
                    }
                }
                val fitting = host.accepted(source, single)
                val refusal = read.refusal ?: if (fitting.size < single.size) "sing-box does not accept some nodes" else null
                val added = imports.addOutbounds(fitting.map { it to tagOf(it) })
                when {
                    added > 0 -> (if (added == 1) "Imported 1 node" else "Imported $added nodes") to true
                    fitting.isNotEmpty() -> "These nodes are already here" to true
                    else -> (refusal ?: "No usable nodes") to false
                }
            }
            host.nodesChanged()
            host.tell(message, error = !good)
            host.imported(message, good)
        }
    }

    /**
     * The QR codes in [pictures] (the screens, a copied picture or a picture file), read as a
     * phone's scanner reads them: node links imported as [importText] imports text, a
     * subscription's link added as a subscription, a KOne key left for the key page. [where] says
     * where none was found ("on the screen"). Says how it went on the dashboard.
     */
    fun importQr(where: String, pictures: suspend () -> List<BufferedImage>) {
        scope.launch {
            val texts = withContext(Dispatchers.Default) { runCatching { pictures() }.getOrDefault(emptyList()).flatMap(QrReader::read).distinct() }
            val keys = texts.filter { it.startsWith("kone:", ignoreCase = true) }
            val links = texts.filterNot { it in keys }.mapNotNull { text -> SubscriptionContent.linkOf(text)?.let { text to it } }.toMap()
            val nodes = texts.filterNot { it in keys || it in links }
            val refused = links.values.distinct().mapNotNull(host::addSubscription)
            val added = links.values.distinct().size - refused.size
            fun say(message: String, good: Boolean) {
                host.tell(message, error = !good)
                host.imported(message, good)
            }
            when {
                texts.isEmpty() -> say("No QR code found $where", good = false)
                nodes.isNotEmpty() -> importText(nodes.joinToString("\n"))
                added > 0 -> say(if (added == 1) "Subscription added from the QR code" else "$added subscriptions added from the QR codes", good = true)
                refused.isNotEmpty() -> say(refused.first(), good = false)
                else -> say("This QR code is a KOne key, not a node: enter it where KOne asks for a key", good = false)
            }
        }
    }

    /** Takes an imported node out; the console's are the console's to take. */
    fun remove(tag: String) {
        if (tag !in host.imported) return
        scope.launch {
            withContext(Dispatchers.IO) { imports.remove(tag) }
            // Asked before the list changes: the choice then leaves the node for Automatic.
            val used = host.selected == tag
            host.nodesChanged(gone = tag)
            if (used) host.restart()
        }
    }

    /** An imported or subscribed node as a share link, handed to [copy]. */
    fun copyLink(tag: String, copy: (String) -> Unit) {
        val own = host.own()
        if (tag !in own.theirs) return
        val link = link(own, tag)
        if (link == null) {
            host.tell("This node cannot be written as a link", error = true)
            return
        }
        copy(link)
        host.tell("Link copied", error = false)
    }

    /** Shows an imported or subscribed node as a QR code (null closes it). */
    fun showQr(tag: String?) {
        if (tag == null) {
            qr = null
            host.refresh()
            return
        }
        val own = host.own()
        if (tag !in own.theirs) return
        val link = link(own, tag)
        if (link == null || qrModules(link) == null) {
            host.tell("This node cannot be shown as a QR code", error = true)
            return
        }
        qr = NodeQr(host.nameOf(tag), link)
        host.refresh()
    }

    /** Opens the form for a new node. */
    fun add() {
        editing = NodeEditing(null, NodeDraft())
        host.refresh()
    }

    /** Opens an imported node in the form; subscribed nodes are their provider's, the console's the owner's. */
    fun edit(tag: String) {
        val outbound = imports.all.getOrNull(host.imported.indexOf(tag)) ?: return
        val draft = NodeForm.read(JsonObject(outbound - "tag"), host.nameOf(tag))
        if (draft == null) {
            host.tell("This node has settings the form cannot show", error = true)
            return
        }
        editing = NodeEditing(tag, draft)
        host.refresh()
    }

    fun cancel() {
        editing = null
        host.refresh()
    }

    /**
     * Saves the node in the form, once sing-box accepts it beside the console's: a new one joins
     * the imported nodes, an edited one takes its old place. A running connection on the edited
     * node starts afresh with it.
     */
    fun save(draft: NodeDraft) {
        val open = editing ?: return
        val outbound = NodeForm.build(draft).getOrElse { failure ->
            editing = open.copy(draft = draft, error = failure.message ?: "The node is not complete")
            host.refresh()
            return
        }
        val managed = host.managed ?: run {
            editing = open.copy(draft = draft, error = "Waiting for the console's nodes")
            host.refresh()
            return
        }
        editing = open.copy(draft = draft, error = null, saving = true)
        host.refresh()
        scope.launch {
            val clean = JsonObject(outbound - "tag")
            val name = tagOf(outbound).orEmpty()
            val accepted = withContext(Dispatchers.IO) { host.accepts(Merge.into(managed, listOf(listOf(outbound))).source) }
            val saved = accepted && withContext(Dispatchers.IO) {
                if (open.tag == null) imports.addOutbounds(listOf(clean to name)) > 0 else imports.replace(host.imported.indexOf(open.tag), clean, name)
            }
            if (!saved) {
                editing = editing?.copy(saving = false, error = if (accepted) "This node is already here" else "sing-box does not accept this node")
                host.refresh()
                return@launch
            }
            editing = null
            // Asked before the list changes: a node renamed by the edit takes the choice to its new name.
            val used = open.tag != null && (host.selected == open.tag || host.inUse() == open.tag)
            host.nodesChanged()
            host.tell(if (open.tag == null) "Node added" else "Node saved", error = false)
            if (used) host.restart()
        }
    }

    /** Every imported and subscribed node as share links, one a line; never the console's. */
    fun exportLinks(): String = host.own().let { own ->
        own.outbounds.mapNotNull { outbound ->
            val tag = tagOf(outbound)?.takeIf { it in own.theirs } ?: return@mapNotNull null
            ShareLinkWriter.write(JsonObject(outbound - "tag"), host.nameOf(tag))
        }.joinToString("\n")
    }

    /** [tag] as a share link when it is the person's to write out and a link can carry it. */
    private fun link(own: Own, tag: String): String? {
        if (tag !in own.theirs) return null
        val outbound = own.outbounds.firstOrNull { tagOf(it) == tag } ?: return null
        return ShareLinkWriter.write(JsonObject(outbound - "tag"), host.nameOf(tag))
    }

    private fun tagOf(outbound: JsonObject) = (outbound["tag"] as? JsonPrimitive)?.contentOrNull
}
