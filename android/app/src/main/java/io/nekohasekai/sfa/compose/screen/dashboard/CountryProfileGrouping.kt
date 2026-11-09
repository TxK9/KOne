package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.database.Profile
import java.util.Locale

internal data class CountryProfileGroup(val key: String, val profiles: List<Profile>)

/** Local organisation metadata only; a label never proves a measured proxy exit. */
internal object CountryProfileGrouping {
    const val UNCLASSIFIED = "ZZ"
    val countries = listOf("US", "GB", "DE", "JP", "SG", "HK", "NL", "KR", "CA", "AU", "FR", "TW")
    private val aliases = mapOf(
        "US" to "\u7f8e\u56fd|\u7f8e\u570b|United States|USA|US|🇺🇸",
        "GB" to "\u82f1\u56fd|\u82f1\u570b|United Kingdom|UK|GB|🇬🇧",
        "DE" to "\u5fb7\u56fd|\u5fb7\u570b|Germany|DE|🇩🇪",
        "JP" to "\u65e5\u672c|Japan|JP|🇯🇵",
        "SG" to "\u65b0\u52a0\u5761|Singapore|SG|🇸🇬",
        "HK" to "\u9999\u6e2f|Hong Kong|HK|🇭🇰",
        "NL" to "\u8377\u5170|\u8377\u862d|Netherlands|NL|🇳🇱",
        "KR" to "\u97e9\u56fd|\u97d3\u570b|South Korea|Korea|KR|🇰🇷",
        "CA" to "\u52a0\u62ff\u5927|Canada|CA|🇨🇦",
        "AU" to "\u6fb3\u5927\u5229\u4e9a|\u6fb3\u5927\u5229\u4e9e|\u6fb3\u6d32|Australia|AU|🇦🇺",
        "FR" to "\u6cd5\u56fd|\u6cd5\u570b|France|FR|🇫🇷",
        "TW" to "\u53f0\u6e7e|\u53f0\u7063|\u81fa\u7063|Taiwan|TW|🇹🇼",
    ).mapValues { (_, words) ->
        words.split('|').map { word ->
            when {
                word.any { it.code >= 128 } -> Regex(Regex.escape(word))
                // Short codes match only in capitals: "de", "ca" and "us" are ordinary words.
                word.length <= 3 -> Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])")
                else -> Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
            }
        }
    }

    fun validKey(key: String): Boolean = key in countries || key == UNCLASSIFIED ||
        (key.startsWith("custom:") && key.removePrefix("custom:").let { it.isNotBlank() && it.length <= 24 && '\n' !in it })

    fun fromName(name: String): String {
        val matches = aliases.filterValues { expressions -> expressions.any { it.containsMatchIn(name) } }.keys
        return matches.singleOrNull() ?: UNCLASSIFIED
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
        if (key.startsWith("custom:")) return key.removePrefix("custom:")
        return Locale("", key).getDisplayCountry(locale)
    }
}
