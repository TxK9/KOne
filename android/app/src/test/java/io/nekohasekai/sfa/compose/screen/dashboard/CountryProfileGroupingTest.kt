package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.database.Profile
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Local nodes", CountryProfileGrouping.label(groups.first().key, Locale.SIMPLIFIED_CHINESE))
        assertEquals("Local nodes", CountryProfileGrouping.label(groups.first().key, Locale.ENGLISH))
    }

    @Test fun customCategoryIsLocalMetadataAndInvalidStoredValuesAreIgnored() {
        val profile = Profile(id = 4, name = "\u82f1\u56fd · \u8282\u70b9")
        assertEquals("GB", CountryProfileGrouping.group(listOf(profile), mapOf(4L to "not-a-country")).first { it.profiles.isNotEmpty() }.key)
        val custom = CountryProfileGrouping.group(listOf(profile), mapOf(4L to "custom:\u5de5\u4f5c"))
        assertEquals("\u5de5\u4f5c", CountryProfileGrouping.label(custom.first { it.profiles.isNotEmpty() }.key, Locale.CHINESE))
        assertFalse(CountryProfileGrouping.validKey("custom:"))
        assertFalse(CountryProfileGrouping.validKey("custom:line\nbreak"))
    }

    @Test fun traditionalChineseNamesUseTheSameCountryCategories() {
        val expected = mapOf(
            "\u7f8e\u570b \u7bc0\u9ede" to "US",
            "\u82f1\u570b-01" to "GB",
            "\u5fb7\u570b" to "DE",
            "\u97d3\u570b \u9996\u723e" to "KR",
            "\u8377\u862d" to "NL",
            "\u6fb3\u6d32" to "AU",
            "\u6fb3\u5927\u5229\u4e9e" to "AU",
            "\u6cd5\u570b" to "FR",
            "\u53f0\u7063 \u5099\u7528" to "TW",
            "\u81fa\u7063" to "TW",
            "HongKong 02" to "HK",
        )
        for ((name, key) in expected) assertEquals(name, key, CountryProfileGrouping.fromName(name))
        assertEquals(CountryProfileGrouping.UNCLASSIFIED, CountryProfileGrouping.fromName("\u7f8e\u570b / \u53f0\u7063"))
    }

    @Test fun localCategoryLabelIsSuppliedByTheLocalizedResource() {
        assertEquals("\u672c\u5730\u8282\u70b9", CountryProfileGrouping.label(CountryProfileGrouping.UNCLASSIFIED, Locale.SIMPLIFIED_CHINESE, "\u672c\u5730\u8282\u70b9"))
        assertEquals(CountryProfileGrouping.LOCAL_NODES_LABEL, CountryProfileGrouping.label(CountryProfileGrouping.UNCLASSIFIED, Locale.ENGLISH))
        assertEquals("custom", CountryProfileGrouping.label("custom:custom", Locale.ENGLISH, "ignored"))
    }
}
