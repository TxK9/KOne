package cc.txwd.kone.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Rules brought over from another client. A Clash (mihomo) profile's `rules:` lines, pasted with
 * or without the YAML around them ("- DOMAIN-SUFFIX,example.com,Proxy"); or v2rayN's routing
 * rules, or an Xray/V2Ray configuration's `routing.rules`, as JSON. Each kind KOne has is read
 * into a [UserRule] and checked as any rule is; the rest are counted and left out. DIRECT (or the
 * direct tag) goes straight out, REJECT (or block) is blocked, and any group or node goes through
 * the node.
 */
object RuleImport {
    /**
     * What came of the text: the rules read, in order, how many were rules KOne could not take,
     * and whether one blocked the ad list, which is KOne's Block ads switch rather than a rule.
     */
    data class Read(val rules: List<UserRule>, val left: Int, val blockAds: Boolean = false)

    private val KINDS = mapOf(
        "DOMAIN" to "domain", "DOMAIN-SUFFIX" to "suffix", "DOMAIN-KEYWORD" to "keyword",
        "IP-CIDR" to "cidr", "IP-CIDR6" to "cidr", "PROCESS-NAME" to "program", "DST-PORT" to "port",
    )

    fun read(text: String): Read {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { return v2ray(it) }
        }
        val rules = ArrayList<UserRule>()
        var left = 0
        for (raw in text.lines()) {
            var line = raw.substringBefore('#').trim()
            if (line.startsWith("- ")) line = line.removePrefix("- ").trim()
            line = line.trim('\'', '"')
            if (line.isEmpty() || line.endsWith(":") || ',' !in line) continue
            val parts = line.split(',').map { it.trim() }
            val type = parts[0].uppercase()
            // MATCH (and FINAL) is what nothing matched: KOne's own setting, not a rule.
            if (type == "MATCH" || type == "FINAL") continue
            if (parts.size < 3) { left++; continue }
            val action = when (parts[2].uppercase()) {
                "DIRECT" -> "direct"
                "REJECT", "REJECT-DROP", "REJECT-TINYGIF" -> "block"
                else -> "proxy"
            }
            val rule = when (type) {
                "GEOSITE" -> UserRule("set", "geosite-" + parts[1].lowercase(), action)
                "GEOIP" -> UserRule("set", "geoip-" + parts[1].lowercase(), action)
                else -> KINDS[type]?.let { UserRule(it, parts[1], action) }
            }
            val value = rule?.let { UserRules.value(it.kind, it.value) }
            if (rule == null || value == null) { left++; continue }
            rules += rule.copy(value = value)
        }
        return Read(rules.distinct(), left)
    }

    /**
     * v2rayN's routing rules (a list of rules, or a routing item holding them as `rules`), or an
     * Xray/V2Ray configuration (`routing.rules`). Each rule's domains, addresses, ports and
     * programs become rules of their own, in order; one turned off is passed over.
     */
    private fun v2ray(json: JsonElement): Read {
        val list = when (json) {
            is JsonArray -> json
            is JsonObject -> (json["rules"] as? JsonArray) ?: ((json["routing"] as? JsonObject)?.get("rules") as? JsonArray)
            else -> null
        } ?: return Read(emptyList(), 0)
        val rules = ArrayList<UserRule>()
        var left = 0
        var ads = false
        fun add(kind: String, value: String, action: String) {
            if (kind == "set" && value == KoneConfig.ADS_RULE_SET && action == "block") {
                ads = true
                return
            }
            val clean = UserRules.value(kind, value)
            if (clean == null) left++ else rules += UserRule(kind, clean, action)
        }
        for (item in list) {
            val rule = item as? JsonObject ?: continue
            if ((rule["enabled"] as? JsonPrimitive)?.booleanOrNull == false) continue
            val tag = ((rule["outboundTag"] ?: rule["balancerTag"]) as? JsonPrimitive)?.contentOrNull?.lowercase()
            val action = when (tag) {
                "direct" -> "direct"
                "block", "blocked", "reject" -> "block"
                else -> "proxy"
            }
            fun texts(key: String): List<String> = when (val value = rule[key]) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                is JsonPrimitive -> value.contentOrNull?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                else -> emptyList()
            }
            var took = false
            for (domain in texts("domain")) {
                took = true
                val (prefix, name) = if (':' in domain) domain.substringBefore(':').lowercase() to domain.substringAfter(':') else "" to domain
                when (prefix) {
                    "geosite" -> add("set", "geosite-" + name.lowercase(), action)
                    "domain" -> add("suffix", name, action)
                    "full" -> add("domain", name, action)
                    "keyword", "" -> add("keyword", name, action)
                    else -> left++ // regexp:, ext: and the like
                }
            }
            for (address in texts("ip")) {
                took = true
                when {
                    // The local network goes straight out in KOne already, and has no list of its own.
                    address.equals("geoip:private", ignoreCase = true) -> left++
                    address.startsWith("geoip:", ignoreCase = true) -> add("set", "geoip-" + address.substringAfter(':').lowercase(), action)
                    else -> add("cidr", address, action)
                }
            }
            for (port in texts("port")) {
                took = true
                add("port", port, action)
            }
            for (program in texts("process")) {
                took = true
                add("program", program.substringAfterLast('\\').substringAfterLast('/'), action)
            }
            // A rule only by protocol, network, inbound or user is not one KOne has.
            if (!took) left++
        }
        return Read(rules.distinct(), left, ads)
    }
}
