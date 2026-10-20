package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's own routing rules (Settings → Routing → Rules) as the Android template writes them.
 * The rules themselves, and how each kind is checked, are the shared core's ([Rules]), the same as
 * KOne for Windows', except that an app is named by its package (com.example.app) rather than a
 * program file. They come after the console's refusals, so none can reach round the Claude block.
 */
object OwnRules {
    /** The kinds the Android App offers, and the sing-box field each fills. */
    val FIELDS = Rules.FIELDS.filterKeys { it in Rules.ANDROID }
    val ACTIONS = Rules.ACTIONS
    const val MOST = Rules.MOST

    /** Each rule checked and written one way by the shared core, anything else dropped, each once. */
    fun clean(rules: List<Rule>): List<Rule> = Rules.clean(rules, Rules.ANDROID)

    /** [text] as a rule of [kind] writes it, or null when it is not one. */
    fun value(kind: String, text: String): String? = if (kind in Rules.ANDROID) Rules.value(kind, text) else null

    fun toJson(rule: Rule): JSONObject = JSONObject().put("kind", rule.kind).put("value", rule.value).put("action", rule.action)

    fun fromJson(json: JSONObject?): Rule? {
        json ?: return null
        return Rule(json.optString("kind"), json.optString("value"), json.optString("action"))
    }

    /**
     * Refused or straight out in every mode; through the node, or the group of a country among
     * [groups] (by code, [Routing.through]), in Rule mode only. A refusal says its method
     * ("default", what sing-box does anyway), so it is never taken for the console's Claude block,
     * which names only domains (see [Routing]): otherwise each re-apply would add it again.
     */
    fun route(rule: Rule, groups: Set<String> = emptySet()): JSONObject = match(rule).apply {
        when (rule.action) {
            "block" -> put("action", "reject").put("method", "default")
            "direct" -> put("outbound", "direct")
            else -> put("clash_mode", "Rule").put("outbound", Rules.via(rule.action)?.takeIf { it in groups }?.let(Routing::through) ?: PairedProfile.PROXY_TAG)
        }
    }

    /** How names matching [rule] are answered, asked where it goes; rules on addresses, ports and address lists (geoip-) have none. */
    fun dns(rule: Rule, groups: Set<String> = emptySet()): JSONObject? {
        if (rule.kind == "cidr" || rule.kind == "port" || (rule.kind == "set" && rule.value.startsWith("geoip-"))) return null
        return match(rule).apply {
            when (rule.action) {
                "block" -> put("action", "reject").put("method", "default")
                "direct" -> put("server", "local-dns")
                else -> put("clash_mode", "Rule").put("server", Rules.via(rule.action)?.takeIf { it in groups }?.let(Rules::dnsThrough) ?: "proxy-dns")
            }
        }
    }

    private fun match(rule: Rule): JSONObject = if (rule.kind == "port") {
        val range = rule.value.split('-')
        if (range.size == 2) JSONObject().put("port_range", JSONArray().put("${range[0]}:${range[1]}"))
        else JSONObject().put("port", JSONArray().put(rule.value.toInt()))
    } else {
        JSONObject().put(FIELDS.getValue(rule.kind), JSONArray().put(rule.value))
    }
}
