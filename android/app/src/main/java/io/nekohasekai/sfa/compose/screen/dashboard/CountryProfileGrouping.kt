package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.database.Profile
import java.util.Locale

internal data class CountryProfileGroup(val key: String, val profiles: List<Profile>)

/** Local organisation metadata only; a label never proves a measured proxy exit. */
internal object CountryProfileGrouping {
    const val UNCLASSIFIED = "ZZ"
    val countries = listOf("US", "GB", "DE", "JP", "SG", "HK", "NL", "KR", "CA", "AU", "FR", "TW")
    // Country names, ISO codes, and common city and airport codes seen in node names; flag emoji are read by flagCountries.
    private val aliases = mapOf(
        "US" to listOf(
            "\u7f8e\u56fd", "\u7f8e\u570b", "\u7f8e\u897f", "\u7f8e\u4e1c", "\u7f8e\u6771", "United States", "USA", "US",
            "\u6d1b\u6749\u77f6", "\u6d1b\u6749\u78ef", "Los Angeles", "\u5723\u4f55\u585e", "\u8056\u4f55\u585e", "\u7845\u8c37",
            "\u77fd\u8c37", "\u897f\u96c5\u56fe", "\u897f\u96c5\u5716", "Seattle", "\u7ebd\u7ea6", "\u7d10\u7d04", "New York",
            "\u829d\u52a0\u54e5", "Chicago", "\u8fbe\u62c9\u65af", "\u9054\u62c9\u65af", "Dallas", "Ashburn", "LAX", "SJC", "JFK", "ORD",
            "DFW", "IAD",
        ),
        "GB" to listOf(
            "\u82f1\u56fd", "\u82f1\u570b", "United Kingdom", "Britain", "England", "UK", "GB", "GBR",
            "\u4f26\u6566", "\u502b\u6566", "London", "LHR",
        ),
        "DE" to listOf(
            "\u5fb7\u56fd", "\u5fb7\u570b", "Germany", "Deutschland", "DE", "DEU", "\u6cd5\u5170\u514b\u798f",
            "\u6cd5\u862d\u514b\u798f", "Frankfurt",
        ),
        "JP" to listOf(
            "\u65e5\u672c", "Japan", "JP", "JPN", "\u4e1c\u4eac", "\u6771\u4eac", "Tokyo", "\u5927\u962a", "Osaka", "NRT", "HND",
            "KIX",
        ),
        "SG" to listOf("\u65b0\u52a0\u5761", "\u72ee\u57ce", "\u7345\u57ce", "Singapore", "SG", "SGP", "SIN"),
        "HK" to listOf("\u9999\u6e2f", "Hong Kong", "Hongkong", "HK", "HKG"),
        "NL" to listOf(
            "\u8377\u5170", "\u8377\u862d", "Netherlands", "Holland", "NL", "NLD", "\u963f\u59c6\u65af\u7279\u4e39", "Amsterdam",
            "AMS",
        ),
        "KR" to listOf(
            "\u97e9\u56fd", "\u97d3\u570b", "South Korea", "Korea", "KR", "KOR", "\u9996\u5c14", "\u9996\u723e", "Seoul", "ICN",
        ),
        "CA" to listOf(
            "\u52a0\u62ff\u5927", "Canada", "CA", "\u591a\u4f26\u591a", "\u591a\u502b\u591a", "Toronto", "\u6e29\u54e5\u534e",
            "\u6eab\u54e5\u83ef", "Vancouver", "YYZ", "YVR",
        ),
        "AU" to listOf(
            "\u6fb3\u5927\u5229\u4e9a", "\u6fb3\u5927\u5229\u4e9e", "\u6fb3\u6d32", "Australia", "AU",
            "\u6089\u5c3c", "\u96ea\u68a8", "Sydney", "\u58a8\u5c14\u672c", "\u58a8\u723e\u672c", "Melbourne", "SYD",
        ),
        "FR" to listOf("\u6cd5\u56fd", "\u6cd5\u570b", "France", "FR", "\u5df4\u9ece", "Paris", "CDG"),
        "TW" to listOf(
            "\u53f0\u6e7e", "\u53f0\u7063", "\u81fa\u7063", "Taiwan", "TW", "TWN", "\u53f0\u5317", "\u81fa\u5317", "Taipei", "TPE",
        ),
    ).mapValues { (_, words) ->
        words.map { word ->
            val bounded = "(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])"
            when {
                // Short codes are case-sensitive: lowercase "de", "ca", "sin" and "ams" are ordinary words in node names.
                word.length <= 3 && word.all { it in 'A'..'Z' } -> Regex(bounded)
                word.all { it.code < 128 } -> Regex(bounded, RegexOption.IGNORE_CASE)
                else -> Regex(Regex.escape(word))
            }
        }
    }

    private const val CUSTOM_PREFIX = "custom:"
    private const val CUSTOM_NAME_LIMIT = 24
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private const val REGIONAL_INDICATOR_Z = 0x1F1FF

    fun validKey(key: String): Boolean = key in countries || key == UNCLASSIFIED ||
        (key.startsWith(CUSTOM_PREFIX) && key.removePrefix(CUSTOM_PREFIX).let { name ->
            name.isNotBlank() && name.length <= CUSTOM_NAME_LIMIT && sanitizeCustomName(name) == name
        })

    /**
     * Keeps a typed category name displayable as one line: control and line
     * separator characters are dropped, unpaired surrogates are removed, and the
     * length limit never splits a surrogate pair.
     */
    fun sanitizeCustomName(input: String): String {
        val result = StringBuilder(minOf(input.length, CUSTOM_NAME_LIMIT))
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            val width = Character.charCount(codePoint)
            index += width
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029) continue
            if (Character.getType(codePoint) == Character.SURROGATE.toInt()) continue
            if (result.length + width > CUSTOM_NAME_LIMIT) break
            result.appendCodePoint(codePoint)
        }
        return result.toString()
    }

    fun fromName(name: String): String {
        val matches = aliases.filterValues { expressions -> expressions.any { it.containsMatchIn(name) } }.keys +
            flagCountries(name).filter { it in countries }
        return matches.singleOrNull() ?: UNCLASSIFIED
    }

    /**
     * Flag emoji are pairs of regional indicators. Adjacent flags must be read
     * pairwise from the start of each run, as renderers do: a plain substring
     * search would find the United States inside the Australia + Singapore pair.
     */
    private fun flagCountries(name: String): Set<String> {
        val result = mutableSetOf<String>()
        var pending: Char? = null
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint !in REGIONAL_INDICATOR_A..REGIONAL_INDICATOR_Z) {
                pending = null
                continue
            }
            val letter = 'A' + (codePoint - REGIONAL_INDICATOR_A)
            val first = pending
            if (first == null) {
                pending = letter
            } else {
                result += "$first$letter"
                pending = null
            }
        }
        return result
    }

    fun group(profiles: List<Profile>, assignments: Map<Long, String>): List<CountryProfileGroup> {
        val grouped = profiles.groupBy { profile ->
            assignments[profile.id]?.takeIf(::validKey) ?: fromName(profile.name)
        }
        // Local nodes include saved entries without a single recognised country.
        // Empty headers remain organising destinations and never create fake nodes.
        val destinations = listOf(UNCLASSIFIED, "GB", "DE")
        val order = destinations + grouped.keys.filterNot { it in destinations }
        return order.map { key -> CountryProfileGroup(key, grouped[key].orEmpty()) }
    }

    /** [localNodes] is the localized name of [UNCLASSIFIED], resolved by the caller from resources. */
    fun label(key: String, localNodes: String, locale: Locale = Locale.getDefault()): String {
        if (key == UNCLASSIFIED) return localNodes
        if (key.startsWith(CUSTOM_PREFIX)) return key.removePrefix(CUSTOM_PREFIX)
        return Locale("", key).getDisplayCountry(locale)
    }
}
