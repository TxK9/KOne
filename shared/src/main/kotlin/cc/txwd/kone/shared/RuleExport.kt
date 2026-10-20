package cc.txwd.kone.shared

/**
 * The person's rules written out as Clash (mihomo) rule lines, one a line, in order: what
 * another client takes, and what [RuleImport] reads back into the same rules on either App. A
 * rule through a country names the country ("DOMAIN-SUFFIX,example.com,Japan"), as a profile's
 * group for it would; through the node is "Proxy".
 */
object RuleExport {
    private val TYPES = mapOf(
        "domain" to "DOMAIN", "suffix" to "DOMAIN-SUFFIX", "keyword" to "DOMAIN-KEYWORD",
        "program" to "PROCESS-NAME", "app" to "PROCESS-NAME", "port" to "DST-PORT",
    )

    /** [rules] as Clash rule lines; a rule no line can say is left out. */
    fun write(rules: List<Rule>): String = rules.mapNotNull(::line).joinToString("\n")

    /** One rule as a Clash rule line, or null when it is not one Clash has. */
    fun line(rule: Rule): String? {
        val target = when (rule.action) {
            "direct" -> "DIRECT"
            "block" -> "REJECT"
            "proxy" -> "Proxy"
            else -> Rules.via(rule.action)?.let { code -> Countries.byCode(code)?.en } ?: return null
        }
        val (type, value) = when (rule.kind) {
            "cidr" -> (if (':' in rule.value) "IP-CIDR6" else "IP-CIDR") to rule.value
            "set" -> when {
                rule.value.startsWith("geosite-") -> "GEOSITE" to rule.value.removePrefix("geosite-")
                rule.value.startsWith("geoip-") -> "GEOIP" to rule.value.removePrefix("geoip-")
                else -> return null
            }
            else -> (TYPES[rule.kind] ?: return null) to rule.value
        }
        // A comma would end the value early; no checked value holds one.
        if (',' in value) return null
        return "$type,$value,$target" + if (type.startsWith("IP-CIDR")) ",no-resolve" else ""
    }
}
