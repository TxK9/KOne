package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.database.Profile
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryProfileGroupingTest {
    @Test fun neutralProviderAndIncidentalLettersDoNotInventARealCountry() {
        for (name in listOf("Verizon", "RUSSELL", "demo", "proxy.example", "US / \u82f1\u56fd")) {
            assertEquals(CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName(name))
        }
        assertEquals("US", CountryProfileGrouping.fromName("[US] Node 1"))
        assertEquals("GB", CountryProfileGrouping.fromName("\u82f1\u56fd · \u5907\u7528"))
        assertEquals("DE", CountryProfileGrouping.fromName("🇩🇪 \u8282\u70b9"))
    }

    @Test fun lowercaseWordsDoNotMatchCountryCodes() {
        assertEquals("JP", CountryProfileGrouping.fromName("Servidor de Japan"))
        assertEquals("FR", CountryProfileGrouping.fromName("Serveur au France"))
        assertEquals("SG", CountryProfileGrouping.fromName("ca Singapore 2"))
        for (name in listOf("contact us", "de", "au relay")) {
            assertEquals(name, CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName(name))
        }
        assertEquals("US", CountryProfileGrouping.fromName("US-West 01"))
        assertEquals("GB", CountryProfileGrouping.fromName("united kingdom 3"))
    }

    @Test fun cityAirportAndTraditionalChineseNamesRecogniseTheirCountry() {
        val expected = mapOf(
            "Tokyo 01" to "JP",
            "\u6771\u4eac IPLC" to "JP",
            "LAX-02" to "US",
            "\u7f8e\u570b \u7f8e\u897f" to "US",
            "US Los Angeles" to "US",
            "London BGP" to "GB",
            "\u6cd5\u5170\u514b\u798f" to "DE",
            "HKG|01" to "HK",
            "Hongkong 3" to "HK",
            "\u81fa\u7063 \u53f0\u5317" to "TW",
            "\u97d3\u570b Seoul" to "KR",
            "\u72ee\u57ce 2" to "SG",
            "\u6fb3\u6d32 Sydney" to "AU",
        )
        for ((name, country) in expected) {
            assertEquals(name, country, CountryProfileGrouping.fromName(name))
        }
    }

    @Test fun lowercaseAirportCodesAndCrossCountryRoutesStayLocal() {
        for (name in listOf("sin city", "ams relay", "order 1", "Tokyo \u2192 London", "LAX / HKG")) {
            assertEquals(name, CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName(name))
        }
        // France's ISO code FRA is also Frankfurt's airport, so neither is an alias.
        assertEquals(CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName("FRA 1"))
    }

    @Test fun manualCategoryWinsAndLocalProfilesKeepTheirIdentityAndOrder() {
        val profiles = listOf(Profile(id = 1, name = "US 1"), Profile(id = 2, name = "Verizon"), Profile(id = 3, name = "Neutral"))
        val groups = CountryProfileGrouping.group(profiles, mapOf(1L to "GB"))
        assertEquals(listOf("ZZ", "GB", "DE"), groups.map { it.key })
        assertEquals(listOf(1L), groups.first { it.key == "GB" }.profiles.map { it.id })
        assertEquals(listOf(2L, 3L), groups.first { it.key == "ZZ" }.profiles.map { it.id })
        assertEquals(profiles.map { it.id }.sorted(), groups.flatMap { it.profiles }.map { it.id }.sorted())
    }

    @Test fun localNodesAlwaysComeFirstWithoutAnEmptyUnitedStatesTemplate() {
        val emptyGroups = CountryProfileGrouping.group(emptyList(), emptyMap())
        assertEquals(listOf("ZZ", "GB", "DE"), emptyGroups.map { it.key })
        assertEquals(emptyList<Profile>(), emptyGroups.flatMap { it.profiles })
        val groups = CountryProfileGrouping.group(
            listOf(Profile(id = 1, name = "\u7f8e\u56fd · \u4e3b\u7ebf"), Profile(id = 2, name = "Japan · \u5907\u7528")),
            emptyMap(),
        )
        assertEquals(listOf("ZZ", "GB", "DE", "US", "JP"), groups.map { it.key })
        assertEquals(listOf(1L), groups.first { it.key == "US" }.profiles.map { it.id })
    }

    @Test fun ambiguousAndUnknownNamesUseTheLocalCategoryWithoutGeoAssumptions() {
        val profiles = listOf(
            Profile(id = 1, name = "Verizon"),
            Profile(id = 2, name = "US / \u82f1\u56fd"),
            Profile(id = 3, name = "192.0.2.1"),
        )
        val groups = CountryProfileGrouping.group(profiles, emptyMap())
        assertEquals(listOf(1L, 2L, 3L), groups.first().profiles.map { it.id })
        assertEquals("\u672c\u5730\u8282\u70b9", CountryProfileGrouping.label(groups.first().key, "\u672c\u5730\u8282\u70b9", Locale.SIMPLIFIED_CHINESE))
        assertEquals("Local nodes", CountryProfileGrouping.label(groups.first().key, "Local nodes", Locale.ENGLISH))
        assertEquals("Japan", CountryProfileGrouping.label("JP", "Local nodes", Locale.ENGLISH))
    }

    @Test fun customCategoryIsLocalMetadataAndInvalidStoredValuesAreIgnored() {
        val profile = Profile(id = 4, name = "\u82f1\u56fd · \u8282\u70b9")
        assertEquals("GB", CountryProfileGrouping.group(listOf(profile), mapOf(4L to "not-a-country")).first { it.profiles.isNotEmpty() }.key)
        val custom = CountryProfileGrouping.group(listOf(profile), mapOf(4L to "custom:\u5de5\u4f5c"))
        assertEquals("\u5de5\u4f5c", CountryProfileGrouping.label(custom.first { it.profiles.isNotEmpty() }.key, "Local nodes", Locale.CHINESE))
        assertFalse(CountryProfileGrouping.validKey("custom:"))
        assertFalse(CountryProfileGrouping.validKey("custom:line\nbreak"))
    }

    @Test fun adjacentFlagsAreReadPairwiseInsteadOfAcrossFlagBoundaries() {
        fun flags(vararg codes: String) = codes.joinToString("") { code ->
            code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        }
        // Mauritius + Senegal spells M-U-S-N; the middle pair is not the United States.
        assertEquals(CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName(flags("MU", "SN") + " Node"))
        // Bangladesh + Spain spells B-D-E-S; the middle pair is not Germany.
        assertEquals(CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName(flags("BD", "ES")))
        // An unlisted flag beside a listed one does not hide it.
        assertEquals("DE", CountryProfileGrouping.fromName(flags("BD", "DE")))
        assertEquals("JP", CountryProfileGrouping.fromName(flags("JP") + " Tokyo"))
        // Two listed countries remain ambiguous.
        assertEquals(CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName(flags("AU", "SG")))
    }

    @Test fun customNamesStayOnOneLineAndNeverSplitAnEmoji() {
        assertEquals("Work", CountryProfileGrouping.sanitizeCustomName("Wo\rr\u0000k\u2028"))
        val emoji = "\uD83D\uDE80"
        val truncated = CountryProfileGrouping.sanitizeCustomName("a".repeat(23) + emoji)
        assertEquals("a".repeat(23), truncated)
        assertEquals("a".repeat(22) + emoji, CountryProfileGrouping.sanitizeCustomName("a".repeat(22) + emoji + "b"))
        assertEquals("ab", CountryProfileGrouping.sanitizeCustomName("a\uD83Db"))
        assertFalse(CountryProfileGrouping.validKey("custom:line\rbreak"))
        assertFalse(CountryProfileGrouping.validKey("custom:\uD83D"))
        assertTrue(CountryProfileGrouping.validKey("custom:" + "a".repeat(22) + emoji))
    }
}
