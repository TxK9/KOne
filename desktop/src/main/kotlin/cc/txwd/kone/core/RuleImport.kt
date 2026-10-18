package cc.txwd.kone.core

/**
 * Rules brought over from a Clash (mihomo) profile: its `rules:` lines, pasted with or without
 * the YAML around them ("- DOMAIN-SUFFIX,example.com,Proxy"). Each kind KOne has is read into a
 * [UserRule] and checked as any rule is; the rest are counted and left out. A policy of DIRECT
 * goes straight out, REJECT is blocked, and any group or node name goes through the node.
 */
object RuleImport {
    /** What came of the text: the rules read, in order, and how many lines were rules KOne could not take. */
    data class Read(val rules: List<UserRule>, val left: Int)

    private val KINDS = mapOf(
        "DOMAIN" to "domain", "DOMAIN-SUFFIX" to "suffix", "DOMAIN-KEYWORD" to "keyword",
        "IP-CIDR" to "cidr", "IP-CIDR6" to "cidr", "PROCESS-NAME" to "program", "DST-PORT" to "port",
    )

    fun read(text: String): Read {
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
}
