package io.nekohasekai.sfa.connection

import cc.txwd.kone.shared.ConfigurationContent
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in HTTPS/QUIC fallback, applied last so earlier Home and direct rules retain priority. */
internal object VideoCompatibility {
    fun apply(content: String, enabled: Boolean): String {
        if (!enabled) return content
        val config = JSONObject(content)
        val outbounds = config.optJSONArray("outbounds") ?: return content
        val proxyTags = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .filter { it.optString("type") !in setOf("direct", "block", "dns") }
            .map { it.getString("tag") }.toSet()
        val route = config.optJSONObject("route") ?: return content
        val source = route.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
        fun append(rule: JSONObject) {
            val previous = rules.optJSONObject(rules.length() - 1)
            if (previous == null || !ConfigurationContent.same(previous.toString(), rule.toString())) rules.put(rule)
        }
        for (index in 0 until source.length()) {
            val rule = source.getJSONObject(index)
            if (rule.optString("outbound") in proxyTags && rule.optString("action", "route") == "route") {
                val match = JSONObject(rule.toString()).apply { remove("outbound"); remove("action") }
                append(JSONObject().put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray().put(match).put(httpsUdp()))
                    .put("action", "reject").put("method", "default").put("no_drop", true))
            }
            append(rule)
        }
        if (route.optString("final") in proxyTags) {
            append(httpsUdp().put("action", "reject").put("method", "default").put("no_drop", true))
        }
        route.put("rules", rules)
        return config.toString()
    }

    private fun httpsUdp() = JSONObject().put("network", "udp").put("port", 443)
}
