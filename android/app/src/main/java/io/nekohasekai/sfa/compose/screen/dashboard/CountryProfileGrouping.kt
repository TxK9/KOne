package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import java.util.Locale

internal data class CountryProfileGroup(val key: String, val profiles: List<Profile>)

/** Local organisation metadata only; a label never proves a measured proxy exit. */
internal object CountryProfileGrouping {
    const val UNCLASSIFIED = "ZZ"
    val countries = listOf("US", "GB", "DE", "JP", "SG", "HK", "NL", "KR", "CA", "AU", "FR", "TW")
    private val aliases = mapOf(
        "US" to "\u7f8e\u56fd|United States|USA|US|🇺🇸",
        "GB" to "\u82f1\u56fd|United Kingdom|UK|GB|🇬🇧",
        "DE" to "\u5fb7\u56fd|Germany|DE|🇩🇪",
        "JP" to "\u65e5\u672c|Japan|JP|🇯🇵",
        "SG" to "\u65b0\u52a0\u5761|Singapore|SG|🇸🇬",
        "HK" to "\u9999\u6e2f|Hong Kong|HK|🇭🇰",
        "NL" to "\u8377\u5170|Netherlands|NL|🇳🇱",
        "KR" to "\u97e9\u56fd|South Korea|KR|🇰🇷",
        "CA" to "\u52a0\u62ff\u5927|Canada|CA|🇨🇦",
        "AU" to "\u6fb3\u5927\u5229\u4e9a|Australia|AU|🇦🇺",
        "FR" to "\u6cd5\u56fd|France|FR|🇫🇷",
        "TW" to "\u53f0\u6e7e|Taiwan|TW|🇹🇼",
    ).mapValues { (_, words) ->
        words.split('|').map { word ->
            if (word.all { it.code < 128 }) Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
            else Regex(Regex.escape(word))
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
        if (key == UNCLASSIFIED) return Application.application.getString(R.string.knine_group_local)
        if (key.startsWith("custom:")) return key.removePrefix("custom:")
        return Locale("", key).getDisplayCountry(locale)
    }
}
