package cc.txwd.kone.shared

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
 * rules, or an Xray/V2Ray configuration's `routing.rules`, as JSON. Each kind the App offers is
 * read into a [Rule] and checked as any rule is; the rest are counted and left out. DIRECT (or
 * the direct tag) goes straight out, REJECT (or block) is blocked, a group or node whose name
 * says one country goes through that country ("🇯🇵 Japan" is via:JP), and any other goes through
 * the node. A whole Clash profile's groups are followed to their first choice, so a group that
 * starts with DIRECT goes straight out. A program becomes a Windows program rule or, where the
 * App offers apps, an Android app rule when it names a package.
 */
object RuleImport {
    /**
     * What came of the text: the rules read, in order, how many were rules KOne could not take,
     * and whether one blocked the ad list, which is KOne's Block ads switch rather than a rule.
     */
    data class Read(val rules: List<Rule>, val left: Int, val blockAds: Boolean = false)

    private val CLASH = mapOf(
        "DOMAIN" to "domain", "DOMAIN-SUFFIX" to "suffix", "DOMAIN-KEYWORD" to "keyword",
        "IP-CIDR" to "cidr", "IP-CIDR6" to "cidr", "PROCESS-NAME" to "program", "DST-PORT" to "port",
    )

    /** [text] read into rules of [kinds] (the App's: [Rules.WINDOWS] or [Rules.ANDROID]). */
    fun read(text: String, kinds: Set<String>): Read {
        val sink = Sink(kinds)
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { v2ray(it, sink); return sink.read() }
        }
        // A profile's YAML gives its rules and groups as written; lines are read one by one otherwise.
        val profile = if (PROFILE.containsMatchIn(text)) ClashProxies.load(text) else null
        val groups = (profile?.get("proxy-groups") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.mapNotNull { group ->
            val name = group["name"]?.toString() ?: return@mapNotNull null
            name to (group["proxies"] as? List<*>).orEmpty().mapNotNull { it?.toString() }
        }.toMap()
        val lines = (profile?.get("rules") as? List<*>)?.mapNotNull { it?.toString()?.trim() } ?: text.lines().map { raw ->
            var line = raw.substringBefore('#').trim()
            if (line.startsWith("- ")) line = line.removePrefix("- ").trim()
            line.trim('\'', '"')
        }
        for (line in lines) {
            if (line.isEmpty() || line.endsWith(":") || ',' !in line) continue
            val parts = line.split(',').map { it.trim() }
            val type = parts[0].uppercase()
            // MATCH (and FINAL) is what nothing matched: KOne's own setting, not a rule.
            if (type == "MATCH" || type == "FINAL") continue
            if (parts.size < 3) { sink.left++; continue }
            val action = action(parts[2], groups)
            when (type) {
                "GEOSITE" -> sink.add("set", "geosite-" + parts[1].lowercase(), action)
                "GEOIP" -> sink.add("set", "geoip-" + parts[1].lowercase(), action)
                else -> CLASH[type]?.let { sink.add(it, parts[1], action) } ?: sink.left++
            }
        }
        return sink.read()
    }

    /** The keys only a Clash profile's YAML has at its top level. */
    private val PROFILE = Regex("""(?m)^(rules|proxy-groups)\s*:""")

    /** Group names that say straight out, or blocked, as common profiles name them; for rules pasted without their groups. */
    private val DIRECT_NAMES = listOf("\u76f4\u8fde", "Direct")
    private val BLOCK_NAMES = listOf("\u62e6\u622a", "\u51c0\u5316", "Reject", "AdBlock")

    /**
     * Where a rule's [target] sends it: DIRECT straight out, REJECT blocked, and a group of the
     * profile ([groups], each name to its choices) as its first choice does, so far as that is one
     * of these. A group not in the profile is read by its name: one saying straight out or blocked
     * ("Direct", "AdBlock", or the same in Chinese), then one saying a country goes through it;
     * anything else through the node.
     */
    private fun action(target: String, groups: Map<String, List<String>>): String {
        fixed(target, groups, 0)?.let { return it }
        if (target !in groups) {
            if (DIRECT_NAMES.any { aliasRegex(it).containsMatchIn(target) }) return "direct"
            if (BLOCK_NAMES.any { aliasRegex(it).containsMatchIn(target) }) return "block"
        }
        return Countries.of(target)?.let { Rules.through(it.code) } ?: "proxy"
    }

    /** Straight out or blocked when [target] is DIRECT or REJECT, or a group that starts with one; null otherwise. */
    private fun fixed(target: String, groups: Map<String, List<String>>, depth: Int): String? = when (target.uppercase()) {
        "DIRECT" -> "direct"
        "REJECT", "REJECT-DROP", "REJECT-TINYGIF" -> "block"
        else -> if (depth < 4) groups[target]?.firstOrNull()?.let { fixed(it, groups, depth + 1) } else null
    }

    /** Where read rules go: checked for the App's kinds, the ad list turned into Block ads, the rest counted. */
    private class Sink(val kinds: Set<String>) {
        val rules = ArrayList<Rule>()
        var left = 0
        var ads = false

        fun add(kind: String, text: String, action: String) {
            if (kind == "set" && text == Rules.ADS && action == "block") {
                ads = true
                return
            }
            // A program on Android is its package, where the client named one; a Windows file (Steam.exe) is not one.
            val fitted = if (kind == "program" && kind !in kinds && "app" in kinds && !Names.isProgram(text)) "app" else kind
            val value = if (fitted in kinds) Rules.value(fitted, text) else null
            if (value == null) left++ else rules += Rule(fitted, value, action)
        }

        fun read() = Read(rules.distinct(), left, ads)
    }

    /**
     * v2rayN's routing rules (a list of rules, or a routing item holding them as `rules`), or an
     * Xray/V2Ray configuration (`routing.rules`). Each rule's domains, addresses, ports and
     * programs become rules of their own, in order; one turned off is passed over.
     */
    private fun v2ray(json: JsonElement, sink: Sink) {
        val list = when (json) {
            is JsonArray -> json
            is JsonObject -> (json["rules"] as? JsonArray) ?: ((json["routing"] as? JsonObject)?.get("rules") as? JsonArray)
            else -> null
        } ?: return
        for (item in list) {
            val rule = item as? JsonObject ?: continue
            if ((rule["enabled"] as? JsonPrimitive)?.booleanOrNull == false) continue
            val tag = ((rule["outboundTag"] ?: rule["balancerTag"]) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val action = when (tag.lowercase()) {
                "direct" -> "direct"
                "block", "blocked", "reject" -> "block"
                // An outbound named for a country ("jp-out") goes through that country.
                else -> Countries.of(tag)?.let { Rules.through(it.code) } ?: "proxy"
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
                    "geosite" -> sink.add("set", "geosite-" + name.lowercase(), action)
                    "domain" -> sink.add("suffix", name, action)
                    "full" -> sink.add("domain", name, action)
                    "keyword", "" -> sink.add("keyword", name, action)
                    else -> sink.left++ // regexp:, ext: and the like
                }
            }
            for (address in texts("ip")) {
                took = true
                when {
                    // The local network goes straight out in KOne already, and has no list of its own.
                    address.equals("geoip:private", ignoreCase = true) -> sink.left++
                    address.startsWith("geoip:", ignoreCase = true) -> sink.add("set", "geoip-" + address.substringAfter(':').lowercase(), action)
                    else -> sink.add("cidr", address, action)
                }
            }
            for (port in texts("port")) {
                took = true
                sink.add("port", port, action)
            }
            for (program in texts("process")) {
                took = true
                sink.add("program", program.substringAfterLast('\\').substringAfterLast('/'), action)
            }
            // A rule only by protocol, network, inbound or user is not one KOne has.
            if (!took) sink.left++
        }
    }
}
