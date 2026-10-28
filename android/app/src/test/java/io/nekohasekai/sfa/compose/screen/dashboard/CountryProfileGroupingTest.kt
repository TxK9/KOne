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
}
