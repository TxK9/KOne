package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.database.Profile
import java.util.Locale

internal data class CountryProfileGroup(val key: String, val profiles: List<Profile>)

/** Local organisation metadata only; a label never proves a measured proxy exit. */
internal object CountryProfileGrouping {
    const val UNCLASSIFIED = "ZZ"
    val countries = listOf("US", "GB", "DE", "JP", "SG", "HK", "NL", "KR", "CA", "AU", "FR", "TW")
    private val aliases = mapOf(
        "US" to "\u7f8e\u56fd|United States|USA|US",
        "GB" to "\u82f1\u56fd|United Kingdom|UK|GB",
        "DE" to "\u5fb7\u56fd|Germany|DE",
        "JP" to "\u65e5\u672c|Japan|JP",
        "SG" to "\u65b0\u52a0\u5761|Singapore|SG",
        "HK" to "\u9999\u6e2f|Hong Kong|HK",
        "NL" to "\u8377\u5170|Netherlands|NL",
        "KR" to "\u97e9\u56fd|South Korea|KR",
        "CA" to "\u52a0\u62ff\u5927|Canada|CA",
        "AU" to "\u6fb3\u5927\u5229\u4e9a|Australia|AU",
        "FR" to "\u6cd5\u56fd|France|FR",
        "TW" to "\u53f0\u6e7e|Taiwan|TW",
    ).mapValues { (_, words) ->
        words.split('|').map { word ->
            if (word.all { it.code < 128 }) Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
            else Regex(Regex.escape(word))
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

    fun label(key: String, locale: Locale = Locale.getDefault()): String {
        if (key == UNCLASSIFIED) return "Local nodes"
        if (key.startsWith(CUSTOM_PREFIX)) return key.removePrefix(CUSTOM_PREFIX)
        return Locale("", key).getDisplayCountry(locale)
    }
}
