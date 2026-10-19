package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One of the person's routing rules: what it matches ([kind] and [value]) and where that goes
 * ([action]: "proxy", "direct" or "block"). They are kept in order; the first that matches wins.
 * Each App places them after the console's refusals, so none can reach round the Claude block.
 */
data class Rule(val kind: String, val value: String, val action: String) {
    fun toJson() = buildJsonObject { put("kind", kind); put("value", value); put("action", action) }

    companion object {
        fun fromJson(json: JsonObject): Rule? {
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            return Rule(text("kind") ?: return null, text("value") ?: return null, text("action") ?: return null)
        }
    }
}

/** The rule kinds, each checked and written one way on every platform. */
object Rules {
    /** Every kind, and the sing-box field it fills. A program is a Windows file name, an app an Android package. */
    val FIELDS = linkedMapOf(
        "domain" to "domain", "suffix" to "domain_suffix", "keyword" to "domain_keyword",
        "cidr" to "ip_cidr", "program" to "process_name", "app" to "package_name", "port" to "port", "set" to "rule_set",
    )

    /** The kinds each App offers. */
    val WINDOWS = setOf("domain", "suffix", "keyword", "cidr", "program", "port", "set")
    val ANDROID = setOf("domain", "suffix", "keyword", "cidr", "app", "port")

    val ACTIONS = listOf("proxy", "direct", "block")
    const val MOST = 256

    /** The category lists the rules may name, at most. */
    const val MOST_SETS = 32

    /** The ad list, which is the Block ads switch, beside the China lists. */
    const val ADS = "geosite-category-ads-all"

    /** A category list as SagerNet publishes them (geosite-netflix, geoip-jp): a safe file name and part of a fixed URL. */
    private val SET_NAME = Regex("""(geosite|geoip)-[a-z0-9][a-z0-9!@_-]{0,63}""")

    /** The templates' own lists; a rule naming one is dropped, as its tag is taken. */
    private val BUILT_IN_SETS = setOf("geosite-cn", "geoip-cn", ADS)

    private val KEYWORD = Regex("""[a-z0-9.-]{2,63}""")

    fun isSetName(name: String) = SET_NAME.matches(name) && name !in BUILT_IN_SETS

    /** [text] as a rule of [kind] writes it, or null when it is not one. */
    fun value(kind: String, text: String): String? {
        val value = text.trim()
        return when (kind) {
            "domain", "suffix" -> value.lowercase().takeIf { it.length <= 253 && Names.isSite(it) }
            "keyword" -> value.lowercase().takeIf { KEYWORD.matches(it) }
            "program" -> value.takeIf { Names.isProgram(it) }
            "app" -> value.takeIf { Names.isPackage(it) }
            "cidr" -> Names.network(value)
            "port" -> Names.port(value)
            "set" -> value.lowercase().takeIf(::isSetName)
            else -> null
        }
    }

    /**
     * Each rule of [kinds] checked and written one way, anything else dropped, each rule once,
     * [MOST] at most, naming [MOST_SETS] lists at most.
     */
    fun clean(rules: List<Rule>, kinds: Set<String>): List<Rule> {
        val seen = HashSet<Rule>()
        val sets = HashSet<String>()
        return rules.asSequence().mapNotNull { rule ->
            if (rule.kind !in kinds || rule.action !in ACTIONS) return@mapNotNull null
            value(rule.kind, rule.value)?.let { Rule(rule.kind, it, rule.action) }
        }.filter(seen::add).filter { rule ->
            rule.kind != "set" || rule.value in sets || (sets.size < MOST_SETS && sets.add(rule.value))
        }.take(MOST).toList()
    }

    /** The category lists [rules] name, each once, in the order they first appear. */
    fun sets(rules: List<Rule>): List<String> = rules.filter { it.kind == "set" }.map { it.value }.distinct()
}
