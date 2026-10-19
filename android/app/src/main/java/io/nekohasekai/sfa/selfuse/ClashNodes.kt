package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.ClashProxies
import cc.txwd.kone.shared.NodeFilter
import org.json.JSONObject

/**
 * The nodes of a Clash profile (its `proxies`), read by the shared core as KOne for Windows reads
 * them: each becomes a paired profile, as a link would. Provider notices dressed as nodes
 * ("Expire: 2026-12-31") are passed over. What could not be read is counted with the first reason.
 */
fun clashNodes(text: String): ClashNodes {
    val read = ClashProxies.read(text)
    val nodes = read.named.filter { (_, name) -> name == null || !NodeFilter.isNotice(name) }
        .map { (outbound, name) -> SharedNode(JSONObject(outbound.toString()).put("tag", ShareLinks.NODE_TAG), name?.trim()?.takeIf(String::isNotEmpty)) }
    return ClashNodes(nodes, read.refused, read.refusal)
}

/** What [clashNodes] found: the nodes, how many proxies were left out, and why the first was. */
class ClashNodes(val nodes: List<SharedNode>, val refused: Int, val refusal: String?)
