package cc.txwd.kone.shared

/**
 * How KOne reads the names people give it, the same on Windows and Android: sites, programs,
 * apps, address ranges and ports. Each is checked and written one way, so a rule typed on one
 * computer is the same rule on another, and the Windows service's Go checks
 * (desktop/service/plan.go, rules.go) are held to these by a test.
 */
object Names {
    private val SITE = Regex("""([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?""")
    private val PROGRAM = Regex("""[^\\/:*?"<>|\u0000-\u001f]{1,128}\.exe""", RegexOption.IGNORE_CASE)
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    private val IPV4 = Regex("""((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])""")
    private val IPV6 = Regex("""[0-9a-f:]{2,39}""")

    /** A domain of two labels or more, written in lower case. */
    fun isSite(text: String) = SITE.matches(text)

    /** A Windows program's file name (WeChat.exe), with no path. */
    fun isProgram(text: String) = PROGRAM.matches(text)

    /** An Android app's package name (com.example.app). */
    fun isPackage(text: String) = text.length <= 255 && PACKAGE.matches(text)

    /** Sites as domains: anything but a plain domain of two labels or more is dropped, each once, [most] at most. */
    fun sites(names: List<String>, most: Int = 256): List<String> {
        val seen = mutableSetOf<String>()
        return names.map { it.trim().lowercase() }
            .filter { it.length <= 253 && SITE.matches(it) && seen.add(it) }
            .take(most)
    }

    /** The site in what the person typed or pasted: a domain, or the one in an address (https://www.example.com/a → www.example.com). */
    fun site(text: String): String? {
        val name = text.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').removePrefix("*.").removePrefix(".").removeSuffix(".")
        return sites(listOf(name)).firstOrNull()
    }

    /** Program file names, each once (whatever its case), 64 at most. */
    fun programs(names: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return names.map { it.trim() }.filter { PROGRAM.matches(it) && seen.add(it.lowercase()) }.take(64)
    }

    /** An address range from its first address ("192.168.1.0/24"); an address alone is that one address. */
    /** One IPv4 or IPv6 address written as rules write them, without a range; null otherwise. */
    fun address(text: String): String? {
        val lower = text.trim().lowercase()
        if ('/' in lower) return null
        return network(lower)?.substringBefore('/')
    }

    fun network(text: String): String? {
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
    fun compressed(bytes: ByteArray): String {
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

    /** One port ("443") or a range ("1000-2000", or "1000:2000"), as "443" or "1000-2000". */
    fun port(text: String): String? {
        val parts = text.replace(':', '-').split('-', limit = 2)
        val from = portNumber(parts[0]) ?: return null
        if (parts.size == 1) return from.toString()
        val to = portNumber(parts[1])?.takeIf { it >= from } ?: return null
        return "$from-$to"
    }

    fun portNumber(text: String): Int? {
        val digits = text.trim()
        if (digits.isEmpty() || digits.length > 5 || (digits.length > 1 && digits[0] == '0') || !digits.all { it in '0'..'9' }) return null
        return digits.toInt().takeIf { it in 1..65535 }
    }
}
