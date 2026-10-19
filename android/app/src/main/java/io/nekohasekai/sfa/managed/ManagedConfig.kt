package io.nekohasekai.sfa.managed

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import io.nekohasekai.sfa.selfuse.Routing
import cc.txwd.kone.shared.ShareLinks
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the console delivers is a sing-box *source*: its outbounds, nothing for Android. The App
 * keeps the proxy outbounds (and whatever they reach through `detour`, so a relay chain stays
 * whole) and puts them into its own template, the same one a share link gets: TUN, DNS, the
 * China rule sets, the modes, and Home when it is on. The only rules kept are the ones that refuse
 * domain names (the console's Claude block, see [refusals]); everything else in the source is dropped.
 */
object ManagedConfig {
    /**
     * How this App turns a source into a configuration. Raised whenever that changes, so a
     * configuration an older App built from the same delivery is built again once.
     * 2: rules refusing domain names are kept.
     */
    const val TEMPLATE = 2

    val PROXY_TYPES = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2",
        "tuic", "ssh", "naive", "anytls", "shadowtls",
    )

    /** Tags the template uses itself; a source outbound with one of these is renamed. */
    private val RESERVED = setOf(PairedProfile.PROXY_TAG, ShareLinks.NODE_TAG, PairedProfile.HOME_TAG, HomeLink.DNS_TAG, Routing.AUTO, cc.txwd.kone.shared.FakeIp.TAG, "direct", "block", "dns")

    /** The KOne configuration for [source]; throws [ManagedException] when it holds no usable node. */
    fun build(source: JSONObject, home: HomeLink.Home, rulesDirectory: String = "rules"): String {
        val all = source.optJSONArray("outbounds")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            ?.map { JSONObject(it.toString()) }
            ?: throw ManagedException(ManagedFailure.ConfigInvalid)
        val proxies = all.filter { it.optString("type") in PROXY_TYPES }
        if (proxies.isEmpty()) throw ManagedException(ManagedFailure.ConfigInvalid, "The configuration contains no usable nodes.")
        val byTag = all.filter { it.optString("tag").isNotBlank() }.associateBy { it.optString("tag") }

        // Whatever a node goes through is kept, however long the chain; a loop or a missing link is refused.
        val support = linkedMapOf<String, JSONObject>()
        fun follow(outbound: JSONObject, depth: Int) {
            val next = outbound.optString("detour").takeIf { it.isNotBlank() } ?: return
            if (depth > 8) throw ManagedException(ManagedFailure.ConfigInvalid)
            if (next in support) return
            val target = byTag[next] ?: throw ManagedException(ManagedFailure.ConfigInvalid)
            if (target.optString("type") !in PROXY_TYPES && target.optString("type") != "direct") throw ManagedException(ManagedFailure.ConfigInvalid)
            support[next] = target
            follow(target, depth + 1)
        }
        proxies.forEach { follow(it, 0) }
        val members = proxies.filter { it.optString("tag") !in support.keys }
        if (members.isEmpty()) throw ManagedException(ManagedFailure.ConfigInvalid)

        // The first node becomes the template's "node"; any other tag that clashes with the template moves aside.
        val used = mutableSetOf<String>()
        val renamed = mutableMapOf<String, String>()
        fun claim(wanted: String): String {
            val base = wanted.ifBlank { "node" }
            var tag = base
            var index = 2
            while (tag in used || tag in RESERVED) tag = "$base-${index++}"
            used += tag
            return tag
        }
        used += ShareLinks.NODE_TAG
        members.forEachIndexed { index, outbound ->
            val old = outbound.optString("tag")
            val tag = if (index == 0) ShareLinks.NODE_TAG else claim(old)
            if (old.isNotBlank()) renamed[old] = tag
            outbound.put("tag", tag)
        }
        support.values.forEach { outbound ->
            val old = outbound.optString("tag")
            val tag = claim(old)
            renamed[old] = tag
            outbound.put("tag", tag)
        }
        (members + support.values).forEach { outbound ->
            outbound.optString("detour").takeIf { it.isNotBlank() }?.let { outbound.put("detour", renamed[it] ?: it) }
        }
        return PairedProfile.buildGroup(members, support.values.toList(), home, rulesDirectory, refusals(source))
    }

    /**
     * The source's rules that refuse domain names and do nothing else, as the console writes them
     * when a managed device is denied access to Claude. A reject rule with any other condition, or
     * without a name, is ignored: it could refuse far more than names.
     */
    internal fun refusals(source: JSONObject): List<JSONObject> {
        val rules = source.optJSONObject("route")?.optJSONArray("rules") ?: return emptyList()
        return (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.mapNotNull { rule ->
            if (rule.optString("action") != "reject" || rule.keys().asSequence().any { it !in REFUSAL_KEYS }) return@mapNotNull null
            val kept = JSONObject()
            for (field in listOf("domain", "domain_suffix")) {
                val names = rule.optJSONArray(field)?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (names.isNotEmpty()) kept.put(field, JSONArray(names))
            }
            if (kept.length() == 0) null else kept.put("action", "reject")
        }
    }

    private val REFUSAL_KEYS = setOf("action", "domain", "domain_suffix")

    /** How many nodes a source offers. */
    fun nodeCount(source: JSONObject): Int = (source.optJSONArray("outbounds") ?: JSONArray()).let { array ->
        (0 until array.length()).count { array.optJSONObject(it)?.optString("type") in PROXY_TYPES }
    }
}
