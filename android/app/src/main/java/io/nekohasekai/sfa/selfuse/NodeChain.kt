package io.nekohasekai.sfa.selfuse

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject

/**
 * A node reached through another one: the phone connects to the node in front, which carries the
 * connection on to the chosen node (sing-box's `detour`). The chain is the person's choice per node
 * and lives beside the saved configuration, which stays as it was built; it is added only to what
 * the service runs, so changing or clearing it never rewrites a node.
 */
object NodeChain {
    /** Outbound kinds that can stand in front of another node. */
    private val CARRIERS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria2", "tuic", "anytls", "ssh", "naive", "shadowtls")

    private val revisionState = mutableIntStateOf(0)

    /** Rises with every change, so the node list and editor read the chains again. */
    val revision: Int get() = revisionState.intValue

    private fun prefs() = Application.application.getSharedPreferences("kone_node_chain", Context.MODE_PRIVATE)

    /** The node [profileId] is reached through, if any. */
    fun via(profileId: Long): Long? = prefs().getLong(profileId.toString(), -1L).takeIf { it > 0 && it != profileId }

    fun set(profileId: Long, via: Long?) {
        prefs().edit().apply { if (via == null || via == profileId) remove(profileId.toString()) else putLong(profileId.toString(), via) }.apply()
        revisionState.intValue += 1
    }

    /** Forgets every chain through or of a deleted node. */
    fun forget(profileId: Long) {
        val store = prefs()
        val editor = store.edit().remove(profileId.toString())
        store.all.forEach { (key, value) -> if (value == profileId) editor.remove(key) }
        editor.apply()
        revisionState.intValue += 1
    }

    /**
     * [exit] run through the node of [front]: every node of [exit] that has no carrier of its own
     * dials out through a copy of [front]'s node. Returns null when [front] has no node that can
     * carry, or carries through a chain of its own (one link at a time keeps the path legible).
     */
    fun chain(exit: String, front: String, frontName: String): String? {
        val config = JSONObject(exit)
        val outbounds = config.optJSONArray("outbounds") ?: return null
        val carrier = nodeOf(JSONObject(front)) ?: return null
        if (carrier.has("detour")) return null
        val taken = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it)?.optString("tag") }.toSet()
        var tag = "Through · $frontName".take(48)
        var n = 2
        while (tag in taken) tag = "Through · $frontName ${n++}".take(52)
        carrier.put("tag", tag)
        val members = selectorMembers(config) ?: return null
        var linked = 0
        for (i in 0 until outbounds.length()) {
            val out = outbounds.optJSONObject(i) ?: continue
            if (out.optString("tag") in members && out.optString("type") in CARRIERS && !out.has("detour")) {
                out.put("detour", tag)
                linked++
            }
        }
        if (linked == 0) return null
        // In front of the direct exit, so the copy sits with the nodes it serves.
        val rebuilt = JSONArray()
        var placed = false
        for (i in 0 until outbounds.length()) {
            val out = outbounds.opt(i)
            if (!placed && out is JSONObject && out.optString("type") == "direct") { rebuilt.put(carrier); placed = true }
            rebuilt.put(out)
        }
        if (!placed) rebuilt.put(carrier)
        config.put("outbounds", rebuilt)
        return config.toString(2)
    }

    /** [content] as the service should run it for [profileId], its chain added when the front node is there to read. */
    // Inline, so a suspending caller can read the front node with the profile store's own calls.
    inline fun applyFor(profileId: Long, content: String, read: (Long) -> Pair<String, String>?): String {
        val front = via(profileId) ?: return content
        val (frontContent, frontName) = read(front) ?: return content
        return runCatching { chain(content, frontContent, frontName) }.getOrNull() ?: content
    }

    /** The node a configuration selects by default: its selector's choice, or its only proxy. */
    private fun nodeOf(config: JSONObject): JSONObject? {
        val outbounds = config.optJSONArray("outbounds") ?: return null
        val all = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
        val selector = all.firstOrNull { it.optString("tag") == PairedProfile.PROXY_TAG && it.optString("type") == "selector" }
        val wanted = selector?.optString("default")?.takeIf { it.isNotBlank() }
            ?: selector?.optJSONArray("outbounds")?.optString(0)
        val node = all.firstOrNull { it.optString("tag") == wanted && it.optString("type") in CARRIERS }
            ?: all.firstOrNull { it.optString("type") in CARRIERS }
        return node?.let { JSONObject(it.toString()) }
    }

    private fun selectorMembers(config: JSONObject): Set<String>? {
        val outbounds = config.optJSONArray("outbounds") ?: return null
        val all = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
        val selector = all.firstOrNull { it.optString("tag") == PairedProfile.PROXY_TAG && it.optString("type") == "selector" }
        val members = selector?.optJSONArray("outbounds")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() }
        return members ?: all.filter { it.optString("type") in CARRIERS }.map { it.optString("tag") }.toSet().ifEmpty { null }
    }
}
