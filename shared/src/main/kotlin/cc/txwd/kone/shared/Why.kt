package cc.txwd.kone.shared

/**
 * Why a connection went the way it did, in the person's own terms: the rule the core matched
 * (as its connection list reports it, "domain_suffix=[example.cn] => route(direct)"), read back
 * against what KOne put in the template: their app and site lists, their own rules, a category,
 * the China lists, the local network, Home, a refusal, the mode, or "everything else". Both Apps say it the
 * same way.
 */
object Why {
    /** The person's lists the explanation is read against. */
    data class Lists(val bypass: List<String>, val direct: List<String>, val proxied: List<String>)

    /** [home] is the tag of Android's Home endpoint, where there is one. */
    fun explain(rule: String?, lists: Lists, home: String? = null): String? {
        val text = rule?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text == "final") return "Everything else"
        val match = text.substringBefore(" => ")
        val action = text.substringAfter(" => ", "")
        val direct = action.startsWith("route(direct")
        val sets = Regex("""rule_set=(\[[^\]]*]|\S+)""").find(match)?.groupValues?.get(1)?.trim('[', ']')?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        return when {
            sets.any { it == "geosite-cn" || it == "geoip-cn" } -> "China list"
            sets.any { it == Rules.ADS } -> "Ads"
            sets.isNotEmpty() -> "Category " + sets.joinToString(", ") { Categories.title(it) ?: it }
            "process_name=sing-box.exe" in match -> "KOne's own test"
            // Refused: the console's block, a rule of the person's own or an ad list, said as what happened.
            action.startsWith("reject") -> "Blocked"
            "package_name=" in match -> "Your rule"
            "process_name=" in match -> if (direct && names(match, "process_name").all { name -> lists.bypass.any { it.equals(name, ignoreCase = true) } }) "Your direct apps" else "Your rule"
            home != null && action.startsWith("route($home") -> "Home"
            "ip_is_private" in match -> "Local network"
            match == "clash_mode=Global" -> "Global mode"
            match == "clash_mode=Direct" -> "Direct mode"
            match == "clash_mode=Rule" -> "Everything else"
            "domain_suffix=" in match && direct && names(match, "domain_suffix").any { it in lists.direct } -> "Your direct sites"
            "domain_suffix=" in match && !direct && names(match, "domain_suffix").any { it in lists.proxied } -> "Your proxied sites"
            else -> "Your rule"
        }
    }

    /** The values of [field] in a rule's text: field=value or field=[a b c]. */
    private fun names(match: String, field: String): List<String> =
        Regex("""(?<![a-z_])$field=(\[[^\]]*]|\S+)""").find(match)?.groupValues?.get(1)?.trim('[', ']')?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
}
