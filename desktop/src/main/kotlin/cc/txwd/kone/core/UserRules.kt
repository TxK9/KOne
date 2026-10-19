package cc.txwd.kone.core

import cc.txwd.kone.shared.Names
import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.Rules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** One of the person's routing rules: the shared core's ([Rule]), on Windows as on Android. */
typealias UserRule = Rule

/**
 * The person's rules as the Windows template uses them: checked by the shared core
 * (shared/src/main/kotlin/cc/txwd/kone/shared/Rules.kt), held to the service's Go checks
 * (desktop/service/rules.go) by the consistency test, and written here as sing-box rules.
 */
object UserRules {
    /** The kinds the Windows App offers, and the sing-box field each fills. */
    val FIELDS = Rules.FIELDS.filterKeys { it in Rules.WINDOWS }
    val ACTIONS = Rules.ACTIONS
    const val MOST = Rules.MOST

    /** The category lists the rules may name, at most. */
    const val MOST_SETS = Rules.MOST_SETS

    fun isSetName(name: String) = Rules.isSetName(name)

    /** Each rule checked and written one way, anything else dropped, each rule once, [MOST] at most, naming [MOST_SETS] lists at most. */
    fun clean(rules: List<UserRule>): List<UserRule> = Rules.clean(rules, Rules.WINDOWS)

    /** The category lists [rules] name, each once, in the order they first appear. */
    fun sets(rules: List<UserRule>): List<String> = Rules.sets(rules)

    /** [text] as a rule of [kind] writes it, or null when it is not one. */
    fun value(kind: String, text: String): String? = if (kind in Rules.WINDOWS) Rules.value(kind, text) else null

    internal fun network(text: String): String? = Names.network(text)

    internal fun compressed(bytes: ByteArray): String = Names.compressed(bytes)

    internal fun portNumber(text: String): Int? = Names.portNumber(text)

    /** The sing-box condition for a checked rule. */
    private fun match(rule: UserRule): Map<String, kotlinx.serialization.json.JsonElement> =
        if (rule.kind == "port") {
            val range = rule.value.split('-')
            if (range.size == 2) mapOf("port_range" to buildJsonArray { add(JsonPrimitive("${range[0]}:${range[1]}")) })
            else mapOf("port" to buildJsonArray { add(JsonPrimitive(rule.value.toInt())) })
        } else {
            mapOf(FIELDS.getValue(rule.kind) to JsonArray(listOf(JsonPrimitive(rule.value))))
        }

    /** Refused or straight out in every mode; through the node in Rule mode only. */
    fun route(rule: UserRule): JsonObject = JsonObject(
        match(rule) + when (rule.action) {
            "block" -> mapOf("action" to JsonPrimitive("reject"))
            "direct" -> mapOf("outbound" to JsonPrimitive("direct"))
            else -> mapOf("clash_mode" to JsonPrimitive("Rule"), "outbound" to JsonPrimitive(KoneConfig.PROXY_TAG))
        },
    )

    /** How names matching [rule] are answered; rules on addresses and ports have none. */
    fun dns(rule: UserRule): JsonObject? {
        if (rule.kind == "cidr" || rule.kind == "port" || (rule.kind == "set" && rule.value.startsWith("geoip-"))) return null
        return JsonObject(
            match(rule) + when (rule.action) {
                "block" -> mapOf("action" to JsonPrimitive("reject"))
                "direct" -> mapOf("server" to JsonPrimitive("local-dns"))
                else -> mapOf("clash_mode" to JsonPrimitive("Rule"), "server" to JsonPrimitive("proxy-dns"))
            },
        )
    }
}
