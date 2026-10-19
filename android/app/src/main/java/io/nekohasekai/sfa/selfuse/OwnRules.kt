package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's own routing rules (Settings → Routing → Rules): what a rule matches ([Rule.kind]
 * and [Rule.value]) and where that goes ([Rule.action]), kept in order, the first match winning.
 * The kinds are KOne for Windows' (desktop/src/main/kotlin/cc/txwd/kone/core/UserRules.kt),
 * checked the same way, except that an app is named by its package (com.example.app) rather than
 * a program file. They come after the console's refusals, so none can reach round the Claude block.
 */
object OwnRules {
    data class Rule(val kind: String, val value: String, val action: String) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("value", value).put("action", action)

        companion object {
            fun fromJson(json: JSONObject?): Rule? {
                json ?: return null
                return Rule(json.optString("kind"), json.optString("value"), json.optString("action"))
            }
        }
    }

    /** The kinds a rule can match on, and the sing-box field each fills. */
    val FIELDS = linkedMapOf(
        "domain" to "domain", "suffix" to "domain_suffix", "keyword" to "domain_keyword",
        "cidr" to "ip_cidr", "app" to "package_name", "port" to "port",
    )
    val ACTIONS = listOf("proxy", "direct", "block")
    const val MOST = 256

    private val SITE = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val KEYWORD = Regex("[a-z0-9.-]{2,63}")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val IPV4 = Regex("((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])")
    private val IPV6 = Regex("[0-9a-f:]{2,39}")

    /** Each rule checked and written one way, anything else dropped, each rule once, [MOST] at most. */
    fun clean(rules: List<Rule>): List<Rule> {
        val seen = HashSet<Rule>()
        return rules.asSequence().mapNotNull { rule ->
            if (rule.kind !in FIELDS || rule.action !in ACTIONS) return@mapNotNull null
            value(rule.kind, rule.value)?.let { Rule(rule.kind, it, rule.action) }
        }.filter(seen::add).take(MOST).toList()
    }

    /** [text] as a rule of [kind] writes it, or null when it is not one. */
    fun value(kind: String, text: String): String? {
        val value = text.trim()
        return when (kind) {
            "domain", "suffix" -> value.lowercase().takeIf { it.length <= 253 && SITE.matches(it) }
            "keyword" -> value.lowercase().takeIf { KEYWORD.matches(it) }
            "app" -> value.takeIf { it.length <= 255 && PACKAGE.matches(it) }
            "cidr" -> network(value)
            "port" -> port(value)
            else -> null
        }
    }

    /** Refused or straight out in every mode; through the node in Rule mode only. */
    fun route(rule: Rule): JSONObject = match(rule).apply {
        when (rule.action) {
            "block" -> put("action", "reject")
            "direct" -> put("outbound", "direct")
            else -> put("clash_mode", "Rule").put("outbound", PairedProfile.PROXY_TAG)
        }
    }

    /** How names matching [rule] are answered; rules on addresses and ports have none. */
    fun dns(rule: Rule): JSONObject? {
        if (rule.kind == "cidr" || rule.kind == "port") return null
        return match(rule).apply {
            when (rule.action) {
                "block" -> put("action", "reject")
                "direct" -> put("server", "local-dns")
                else -> put("clash_mode", "Rule").put("server", "proxy-dns")
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

    /** One port ("443") or a range ("1000-2000", or "1000:2000"). */
    private fun port(text: String): String? {
        val parts = text.replace(':', '-').split('-', limit = 2)
        val from = portNumber(parts[0]) ?: return null
        if (parts.size == 1) return from.toString()
        val to = portNumber(parts[1])?.takeIf { it >= from } ?: return null
        return "$from-$to"
    }

    private fun portNumber(text: String): Int? {
        val digits = text.trim()
        if (digits.isEmpty() || digits.length > 5 || (digits.length > 1 && digits[0] == '0') || !digits.all { it in '0'..'9' }) return null
        return digits.toInt().takeIf { it in 1..65535 }
    }

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
        // An IPv4 address written as IPv6 (::ffff:a.b.c.d) is refused, as the Windows App does.
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

    /** An IPv6 address in its short form (RFC 5952). */
    private fun compressed(bytes: ByteArray): String {
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
}
