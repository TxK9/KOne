package cc.txwd.kone.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One of the person's routing rules: what it matches ([kind] and [value]) and where that goes
 * ([action]). They are kept in order; the first that matches wins. They come after the Claude
 * block, so none of them can reach round it.
 */
data class UserRule(val kind: String, val value: String, val action: String) {
    fun toJson() = buildJsonObject { put("kind", kind); put("value", value); put("action", action) }

    companion object {
        fun fromJson(json: JsonObject): UserRule? {
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            return UserRule(text("kind") ?: return null, text("value") ?: return null, text("action") ?: return null)
        }
    }
}

/**
 * The person's rules as the template uses them, checked and written one way exactly as the
 * service does (desktop/service/rules.go), so the two build the same configuration.
 */
object UserRules {
    /** The kinds a rule can match on, and the sing-box field each fills. */
    val FIELDS = linkedMapOf(
        "domain" to "domain", "suffix" to "domain_suffix", "keyword" to "domain_keyword",
        "cidr" to "ip_cidr", "program" to "process_name", "port" to "port",
    )
    val ACTIONS = listOf("proxy", "direct", "block")
    const val MOST = 256

    /** Each rule checked and written one way, anything else dropped, each rule once, [MOST] at most. */
    fun clean(rules: List<UserRule>): List<UserRule> {
        val seen = HashSet<UserRule>()
        return rules.asSequence().mapNotNull { rule ->
            if (rule.kind !in FIELDS || rule.action !in ACTIONS) return@mapNotNull null
            value(rule.kind, rule.value)?.let { UserRule(rule.kind, it, rule.action) }
        }.filter(seen::add).take(MOST).toList()
    }

    /** [text] as a rule of [kind] writes it, or null when it is not one. */
    fun value(kind: String, text: String): String? {
        val value = text.trim()
        return when (kind) {
            "domain", "suffix" -> value.lowercase().takeIf { it.length <= 253 && KoneConfig.isSite(it) }
            "keyword" -> value.lowercase().takeIf { KEYWORD.matches(it) }
            "program" -> value.takeIf { KoneConfig.isProgram(it) }
            "cidr" -> network(value)
            "port" -> port(value)
            else -> null
        }
    }

    private val KEYWORD = Regex("""[a-z0-9.-]{2,63}""")
    private val IPV4 = Regex("""((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])""")
    private val IPV6 = Regex("""[0-9a-f:]{2,39}""")

    /** An address range from its first address ("192.168.1.0/24"); an address alone is that one address. */
    internal fun network(text: String): String? {
        val lower = text.lowercase()
        val address = lower.substringBefore('/')
        val bits = if ('/' in lower) lower.substringAfter('/') else null
        val bytes = when {
            IPV4.matches(address) -> address.split('.').map { it.toInt().toByte() }.toByteArray()
            ':' in address && IPV6.matches(address) -> ipv6(address) ?: return null
            else -> return null
        }
        // An IPv4 address written as IPv6 (::ffff:a.b.c.d in hex) is refused, as the service does.
        if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) return null
        val width = bytes.size * 8
        val prefix = if (bits == null) width else {
            if (bits.isEmpty() || (bits.length > 1 && bits[0] == '0') || !bits.all { it in '0'..'9' } || bits.length > 3) return null
            bits.toInt().takeIf { it in 1..width } ?: return null
        }
        for (bit in prefix until width) bytes[bit / 8] = (bytes[bit / 8].toInt() and (0x80 ushr (bit % 8)).inv()).toByte()
        val shown = if (bytes.size == 4) bytes.joinToString(".") { (it.toInt() and 0xff).toString() } else compressed(bytes)
        return "$shown/$prefix"
    }

    /** The 16 bytes of an IPv6 address in hex groups and colons, at most one "::"; null when it is not one. */
    private fun ipv6(text: String): ByteArray? {
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map { group ->
            if (group.isEmpty() || group.length > 4) return null
            group.toIntOrNull(16) ?: return null
        }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val all = if (halves.size == 2) {
            if (head.size + tail.size > 7) return null
            head + List(8 - head.size - tail.size) { 0 } + tail
        } else {
            if (head.size != 8) return null
            head
        }
        return ByteArray(16) { i -> (all[i / 2] shr (if (i % 2 == 0) 8 else 0) and 0xff).toByte() }
    }

    /** An IPv6 address in its short form (RFC 5952): the first longest run of two or more zero groups as "::". */
    internal fun compressed(bytes: ByteArray): String {
        val groups = (0 until 8).map { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
        var bestStart = -1
        var bestLength = 1
        var index = 0
        while (index < 8) {
            if (groups[index] != 0) { index++; continue }
            val start = index
            while (index < 8 && groups[index] == 0) index++
            if (index - start > bestLength) { bestStart = start; bestLength = index - start }
        }
        val hex = groups.map { Integer.toHexString(it) }
        if (bestStart < 0) return hex.joinToString(":")
        return hex.take(bestStart).joinToString(":") + "::" + hex.drop(bestStart + bestLength).joinToString(":")
    }

    /** One port ("443") or a range ("1000-2000", or "1000:2000"). */
    private fun port(text: String): String? {
        val parts = text.replace(':', '-').split('-', limit = 2)
        val from = portNumber(parts[0]) ?: return null
        if (parts.size == 1) return from.toString()
        val to = portNumber(parts[1])?.takeIf { it >= from } ?: return null
        return "$from-$to"
    }

    internal fun portNumber(text: String): Int? {
        val digits = text.trim()
        if (digits.isEmpty() || digits.length > 5 || (digits.length > 1 && digits[0] == '0') || !digits.all { it in '0'..'9' }) return null
        return digits.toInt().takeIf { it in 1..65535 }
    }

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
        if (rule.kind == "cidr" || rule.kind == "port") return null
        return JsonObject(
            match(rule) + when (rule.action) {
                "block" -> mapOf("action" to JsonPrimitive("reject"))
                "direct" -> mapOf("server" to JsonPrimitive("local-dns"))
                else -> mapOf("clash_mode" to JsonPrimitive("Rule"), "server" to JsonPrimitive("proxy-dns"))
            },
        )
    }
}
