package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.NodeFilter
import cc.txwd.kone.shared.SubscriptionContent
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.managed.ManagedConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a remote profile's link sends, made into something KOne runs. A whole sing-box
 * configuration (one with inbounds) is kept as sent, as sing-box for Android always has. What
 * most providers send instead (share links one a line, the same as one base64 block, a Clash
 * profile, or a sing-box node list) is read by the shared core as KOne for Windows reads a
 * subscription, provider notices left out, and becomes KOne's own template around those nodes:
 * the first selected, the rest to choose from, Routing, Home and the Claude block applied as to
 * any other. The remote profile's own interval refreshes it.
 */
object SubscriptionProfile {
    /** Whether [body] is a whole sing-box configuration, which a remote profile keeps as sent. */
    fun isWholeConfig(body: String): Boolean {
        val json = runCatching { JSONObject(body.trim().removePrefix("﻿")) }.getOrNull() ?: return false
        return (json.optJSONArray("inbounds")?.length() ?: 0) > 0
    }

    /** The nodes in [body] in KOne's template; throws [IllegalArgumentException] saying why when there are none. */
    fun build(body: String, home: HomeLink.Home, rulesDirectory: String = "rules"): String {
        val read = SubscriptionContent.read(body, ManagedConfig.PROXY_TYPES)
        val kept = NodeFilter.apply(read.nodes, "").nodes
        require(kept.isNotEmpty()) { read.refusal ?: "No nodes found in the subscription" }
        val source = JSONObject().put("outbounds", JSONArray(kept.map { JSONObject(it.toString()) }))
        return ManagedConfig.build(source, home, rulesDirectory)
    }

    /** [body] as the remote profile keeps it: as sent when whole, else built here with the phone's Home settings. */
    fun prepare(body: String): String =
        if (isWholeConfig(body)) body else build(body, HomeSettings.current(), InitialRuleSets.ensure(Application.application))
}
