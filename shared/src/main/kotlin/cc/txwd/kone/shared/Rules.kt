package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One of the person's routing rules: what it matches ([kind] and [value]) and where that goes
 * ([action]: "proxy", "direct", "block", or through a chosen country's nodes, "via:JP"). They
 * are kept in order; the first that matches wins. Each App places them after the console's
 * refusals, so none can reach round the Claude block.
 *
 * A rule through the node or a country also says how its node is picked ([pick], [Policies]):
 * "" as before (the node in use, or a country's fastest), "fastest", or "fallback", the first in
 * order that answers. Only an App that offers picks keeps one ([Rules.clean]).
 */
data class Rule(val kind: String, val value: String, val action: String, val pick: String = "") {
    fun toJson() = buildJsonObject {
        put("kind", kind); put("value", value); put("action", action)
        if (pick.isNotEmpty()) put("pick", pick)
    }

    companion object {
        fun fromJson(json: JsonObject): Rule? {
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            return Rule(text("kind") ?: return null, text("value") ?: return null, text("action") ?: return null, text("pick").orEmpty())
        }
    }
}

/** The rule kinds, each checked and written one way on every platform. */
object Rules {
    /** Every kind, and the sing-box field it fills. A program is a Windows file name, an app an Android package. */
    val FIELDS = linkedMapOf(
        "domain" to "domain", "suffix" to "domain_suffix", "keyword" to "domain_keyword",
        "cidr" to "ip_cidr", "program" to "process_name", "app" to "package_name", "port" to "port", "set" to "rule_set",
        "url" to "rule_set",
    )

    /** The kinds each App offers. */
    val WINDOWS = setOf("domain", "suffix", "keyword", "cidr", "program", "port", "set")
    val ANDROID = setOf("domain", "suffix", "keyword", "cidr", "app", "port", "set", "url")

    val ACTIONS = listOf("proxy", "direct", "block")
    const val MOST = 256

    /**
     * Through one country's nodes, by its code: "via:JP" goes through the fastest node whose name
     * says Japan, in Rule mode, as "proxy" goes through the node in use; while no node says that
     * country, through the node in use. Direct mode still sends nothing through a node, and Global
     * everything through the node in use.
     */
    private val VIA = Regex("""via:[A-Z]{2}""")

    /** The category lists the rules may name, at most. */
    const val MOST_SETS = 32

    /** The lists of the person's own, by address ("url" rules, [RuleProviders]), at most. */
    const val MOST_URLS = 16

    /** How a rule through the node or a country picks its node, besides the default ([Policies]). */
    val PICKS = listOf(Policies.FASTEST, Policies.FALLBACK)

    /** Whether [action] goes through a node, so a pick means something for it. */
    fun throughNode(action: String) = action == "proxy" || VIA.matches(action)

    /** The ad list, which is the Block ads switch, beside the China lists. */
    const val ADS = "geosite-category-ads-all"

    /** A category list as SagerNet publishes them (geosite-netflix, geoip-jp): a safe file name and part of a fixed URL. */
    private val SET_NAME = Regex("""(geosite|geoip)-[a-z0-9][a-z0-9!@_-]{0,63}""")

    /** The templates' own lists; a rule naming one is dropped, as its tag is taken. */
    private val BUILT_IN_SETS = setOf("geosite-cn", "geoip-cn", ADS)

    private val KEYWORD = Regex("""[a-z0-9.-]{2,63}""")

    fun isSetName(name: String) = SET_NAME.matches(name) && name !in BUILT_IN_SETS

    /** Whether [action] is one a rule takes: one of [ACTIONS], or through a country ("via:JP"). */
    fun isAction(action: String) = action in ACTIONS || VIA.matches(action)

    /** The country [action] goes through ("JP" for "via:JP"), or null when it goes another way. */
    fun via(action: String): String? = if (VIA.matches(action)) action.substring(4) else null

    /** The action that goes through the nodes of the country [code] names. */
    fun through(code: String) = "via:${code.uppercase()}"

    /** How the DNS servers asked through one country's nodes are tagged: [dnsThrough]. */
    const val DNS_THROUGH = "proxy-dns-"

    /**
     * The DNS server for the names of the rules through [code], asked through that country's
     * nodes as the node's DNS (proxy-dns) is through the node in use, so a site answers as it
     * would in that country.
     */
    fun dnsThrough(code: String) = DNS_THROUGH + code

    /** The countries [rules] go through, each once, in the order they first appear. */
    fun countries(rules: List<Rule>): List<String> = rules.mapNotNull { via(it.action) }.distinct()

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
            "url" -> RuleProviders.clean(value)
            else -> null
        }
    }

    /**
     * Each rule of [kinds] checked and written one way, anything else dropped, each rule once,
     * [MOST] at most, naming [MOST_SETS] category lists and [MOST_URLS] own lists at most. A pick
     * is kept where [picks] says the App offers them and the rule goes through a node; otherwise
     * it is let go, so the rule is as it always was.
     */
    fun clean(rules: List<Rule>, kinds: Set<String>, picks: Boolean = false): List<Rule> {
        val seen = HashSet<Rule>()
        val sets = HashSet<String>()
        val urls = HashSet<String>()
        return rules.asSequence().mapNotNull { rule ->
            if (rule.kind !in kinds || !isAction(rule.action)) return@mapNotNull null
            // A country's group is its fastest already, so that pick is the default there.
            val pick = rule.pick.takeIf { picks && it in PICKS && throughNode(rule.action) && !(it == Policies.FASTEST && via(rule.action) != null) }.orEmpty()
            value(rule.kind, rule.value)?.let { Rule(rule.kind, it, rule.action, pick) }
        }.filter(seen::add).filter { rule ->
            when (rule.kind) {
                "set" -> rule.value in sets || (sets.size < MOST_SETS && sets.add(rule.value))
                "url" -> rule.value in urls || (urls.size < MOST_URLS && urls.add(rule.value))
                else -> true
            }
        }.take(MOST).toList()
    }

    /** The category lists [rules] name, each once, in the order they first appear. */
    fun sets(rules: List<Rule>): List<String> = rules.filter { it.kind == "set" }.map { it.value }.distinct()

    /** The person's own lists [rules] name by address, each once, in the order they first appear. */
    fun urls(rules: List<Rule>): List<String> = rules.filter { it.kind == "url" }.map { it.value }.distinct()
}
