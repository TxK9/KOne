package cc.txwd.kone.shared

/**
 * Country names, common city names and flag emoji used to recognise a node's
 * country from its name. This table only describes what a name says; it never
 * proves where a proxy actually exits.
 */
internal data class Country(val code: String, val zh: String, val en: String, val words: List<String>) {
    val flag: String get() = flagEmoji(code)
    val label: String get() = en

    private val matchers: List<Regex> by lazy {
        (listOf(zh, en, flag) + words).distinct().map(::aliasRegex)
    }

    fun matches(name: String): Boolean = matchers.any { it.containsMatchIn(name) }
}

internal object Countries {
    // A two-letter code is only an alias when it is not also an ordinary word.
    private fun c(code: String, zh: String, en: String, vararg words: String, codeIsAlias: Boolean = true) =
        Country(code, zh, en, (if (codeIsAlias) listOf(code) else emptyList()) + words)

    val all: List<Country> = listOf(
        c("US", "\u7f8e\u56fd", "United States", "USA", "America", "\u6d1b\u6749\u77f6", "Los Angeles", "\u897f\u96c5\u56fe", "Seattle", "\u5723\u4f55\u585e", "San Jose", "\u7ebd\u7ea6", "New York", "\u7845\u8c37", "Silicon Valley", "\u829d\u52a0\u54e5", "Chicago", "\u8fbe\u62c9\u65af", "Dallas", "\u8fc8\u963f\u5bc6", "Miami", "\u51e4\u51f0\u57ce", "Phoenix", "\u5f17\u5409\u5c3c\u4e9a", "Virginia", "\u963f\u4ec0\u672c", "Ashburn"),
        c("GB", "\u82f1\u56fd", "United Kingdom", "UK", "Britain", "England", "\u4f26\u6566", "London", "\u66fc\u5f7b\u65af\u7279", "Manchester"),
        c("DE", "\u5fb7\u56fd", "Germany", "\u6cd5\u5170\u514b\u798f", "Frankfurt", "\u67cf\u6797", "Berlin", "\u6155\u5c3c\u9ed1", "Munich"),
        c("JP", "\u65e5\u672c", "Japan", "\u4e1c\u4eac", "Tokyo", "\u5927\u962a", "Osaka", "\u540d\u53e4\u5c4b", "Nagoya", "\u798f\u5188", "Fukuoka", "\u672d\u5e4c", "Sapporo", "\u57fc\u7389", "Saitama"),
        c("SG", "\u65b0\u52a0\u5761", "Singapore", "\u72ee\u57ce"),
        c("HK", "\u9999\u6e2f", "Hong Kong", "HongKong"),
        c("TW", "\u53f0\u6e7e", "Taiwan", "\u53f0\u5317", "Taipei", "\u65b0\u5317", "\u5f70\u5316", "Changhua"),
        c("KR", "\u97e9\u56fd", "South Korea", "Korea", "\u9996\u5c14", "Seoul", "\u6625\u5ddd", "Chuncheon"),
        c("NL", "\u8377\u5170", "Netherlands", "\u963f\u59c6\u65af\u7279\u4e39", "Amsterdam"),
        c("FR", "\u6cd5\u56fd", "France", "\u5df4\u9ece", "Paris", "\u9a6c\u8d5b", "Marseille"),
        c("CA", "\u52a0\u62ff\u5927", "Canada", "\u591a\u4f26\u591a", "Toronto", "\u6e29\u54e5\u534e", "Vancouver", "\u8499\u7279\u5229\u5c14", "Montreal"),
        c("AU", "\u6fb3\u5927\u5229\u4e9a", "Australia", "\u6fb3\u6d32", "\u6089\u5c3c", "Sydney", "\u58a8\u5c14\u672c", "Melbourne"),
        c("RU", "\u4fc4\u7f57\u65af", "Russia", "\u83ab\u65af\u79d1", "Moscow", "\u5723\u5f7c\u5f97\u5821"),
        c("IN", "\u5370\u5ea6", "India", "\u5b5f\u4e70", "Mumbai", "\u73ed\u52a0\u7f57\u5c14", "Bangalore", codeIsAlias = false),
        c("BR", "\u5df4\u897f", "Brazil", "\u5723\u4fdd\u7f57", "Sao Paulo", "São Paulo"),
        c("TR", "\u571f\u8033\u5176", "Turkey", "Türkiye", "\u4f0a\u65af\u5766\u5e03\u5c14", "Istanbul"),
        c("AE", "\u963f\u8054\u914b", "United Arab Emirates", "UAE", "\u8fea\u62dc", "Dubai"),
        c("CH", "\u745e\u58eb", "Switzerland", "\u82cf\u9ece\u4e16", "Zurich"),
        c("SE", "\u745e\u5178", "Sweden", "\u65af\u5fb7\u54e5\u5c14\u6469", "Stockholm"),
        c("IT", "\u610f\u5927\u5229", "Italy", "\u7c73\u5170", "Milan", codeIsAlias = false),
        c("ES", "\u897f\u73ed\u7259", "Spain", "\u9a6c\u5fb7\u91cc", "Madrid"),
        c("PL", "\u6ce2\u5170", "Poland", "\u534e\u6c99", "Warsaw"),
        c("FI", "\u82ac\u5170", "Finland", "\u8d6b\u5c14\u8f9b\u57fa", "Helsinki"),
        c("NO", "\u632a\u5a01", "Norway", "\u5965\u65af\u9646", "Oslo", codeIsAlias = false),
        c("IE", "\u7231\u5c14\u5170", "Ireland", "\u90fd\u67cf\u6797", "Dublin"),
        c("AT", "\u5965\u5730\u5229", "Austria", "\u7ef4\u4e5f\u7eb3", "Vienna", codeIsAlias = false),
        c("BE", "\u6bd4\u5229\u65f6", "Belgium", "\u5e03\u9c81\u585e\u5c14", "Brussels", codeIsAlias = false),
        c("DK", "\u4e39\u9ea6", "Denmark", "\u54e5\u672c\u54c8\u6839", "Copenhagen"),
        c("PT", "\u8461\u8404\u7259", "Portugal", "\u91cc\u65af\u672c", "Lisbon"),
        c("CZ", "\u6377\u514b", "Czech", "\u5e03\u62c9\u683c", "Prague"),
        c("UA", "\u4e4c\u514b\u5170", "Ukraine", "\u57fa\u8f85", "Kyiv"),
        c("IL", "\u4ee5\u8272\u5217", "Israel", "\u7279\u62c9\u7ef4\u592b", "Tel Aviv"),
        c("ZA", "\u5357\u975e", "South Africa", "\u7ea6\u7ff0\u5185\u65af\u5821", "Johannesburg"),
        c("MX", "\u58a8\u897f\u54e5", "Mexico"),
        c("AR", "\u963f\u6839\u5ef7", "Argentina", "\u5e03\u5b9c\u8bfa\u65af\u827e\u5229\u65af", "Buenos Aires"),
        c("CL", "\u667a\u5229", "Chile", "\u5723\u5730\u4e9a\u54e5", "Santiago"),
        c("TH", "\u6cf0\u56fd", "Thailand", "\u66fc\u8c37", "Bangkok"),
        c("VN", "\u8d8a\u5357", "Vietnam", "\u80e1\u5fd7\u660e", "Ho Chi Minh", "\u6cb3\u5185", "Hanoi"),
        c("MY", "\u9a6c\u6765\u897f\u4e9a", "Malaysia", "\u5409\u9686\u5761", "Kuala Lumpur", codeIsAlias = false),
        c("PH", "\u83f2\u5f8b\u5bbe", "Philippines", "\u9a6c\u5c3c\u62c9", "Manila"),
        c("ID", "\u5370\u5ea6\u5c3c\u897f\u4e9a", "Indonesia", "\u5370\u5c3c", "\u96c5\u52a0\u8fbe", "Jakarta", codeIsAlias = false),
        c("NZ", "\u65b0\u897f\u5170", "New Zealand", "\u5965\u514b\u5170", "Auckland"),
        c("MO", "\u6fb3\u95e8", "Macau", "Macao"),
        c("LU", "\u5362\u68ee\u5821", "Luxembourg"),
        c("RO", "\u7f57\u9a6c\u5c3c\u4e9a", "Romania", "\u5e03\u52a0\u52d2\u65af\u7279", "Bucharest"),
        c("HU", "\u5308\u7259\u5229", "Hungary", "\u5e03\u8fbe\u4f69\u65af", "Budapest"),
        c("BG", "\u4fdd\u52a0\u5229\u4e9a", "Bulgaria", "\u7d22\u975e\u4e9a", "Sofia"),
        c("KZ", "\u54c8\u8428\u514b\u65af\u5766", "Kazakhstan", "\u963f\u62c9\u6728\u56fe", "Almaty"),
        c("SA", "\u6c99\u7279", "Saudi Arabia", "\u5229\u96c5\u5f97", "Riyadh"),
        c("EG", "\u57c3\u53ca", "Egypt", "\u5f00\u7f57", "Cairo"),
        c("NG", "\u5c3c\u65e5\u5229\u4e9a", "Nigeria", "\u62c9\u5404\u65af", "Lagos"),
        c("CN", "\u4e2d\u56fd", "China", "\u56de\u56fd"),
    )

    private val codeIndex = all.associateBy { it.code }

    fun byCode(code: String?): Country? = code?.uppercase()?.let { codeIndex[it] }

    /** The one country [name] says ("Tokyo 01" is Japan), or null when it says none or several. */
    fun of(name: String): Country? = all.filter { it.matches(name) }.singleOrNull()

    /** A category literally named after a country recognises that country's aliases too. */
    fun byName(name: String): Country? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return all.firstOrNull { it.zh == trimmed || it.en.equals(trimmed, ignoreCase = true) || it.flag == trimmed }
    }

    fun search(query: String): List<Country> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { it.zh.contains(q) || it.en.contains(q, ignoreCase = true) || it.code.equals(q, ignoreCase = true) }
    }
}

/** Regional indicator pair; Android renders it as the country's flag. */
internal fun flagEmoji(code: String): String {
    val upper = code.uppercase()
    if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return ""
    return upper.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** ASCII words need letter boundaries so "RUSSELL" is not Russia; other scripts match as written. */
internal fun aliasRegex(word: String): Regex = if (word.all { it.code < 128 }) {
    val shortCode = word.length <= 3 && word.all { it in 'A'..'Z' }
    Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])", if (shortCode) emptySet() else setOf(RegexOption.IGNORE_CASE))
} else {
    Regex(Regex.escape(word))
}
