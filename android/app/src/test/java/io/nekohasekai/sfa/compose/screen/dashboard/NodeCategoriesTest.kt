package io.nekohasekai.sfa.compose.screen.dashboard

import cc.txwd.kone.shared.Countries
import cc.txwd.kone.shared.flagEmoji
import io.nekohasekai.sfa.database.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCategoriesTest {
    private fun state(vararg categories: NodeCategory, assignments: Map<Long, String> = emptyMap()) =
        NodeCategoryState(categories = categories.toList(), assignments = assignments)

    @Test fun nothingIsPresetSoEveryNodeStartsInTheFallback() {
        val profiles = listOf(Profile(id = 1, name = "Tokyo-01"), Profile(id = 2, name = "\u82f1\u56fd · \u5907\u7528"))
        val groups = NodeCategories.group(profiles, NodeCategoryState())
        assertEquals(listOf(NodeCategories.FALLBACK_ID), groups.map { it.id })
        assertEquals("Local nodes", groups.single().name)
        assertEquals(listOf(1L, 2L), groups.single().profiles.map { it.id })
    }

    @Test fun aFlaggedCategoryRecognisesCountryAndCityNames() {
        val japan = NodeCategory("c1", "\u5e38\u7528\u65e5\u672c", "JP")
        for (name in listOf("Tokyo-03", "\u4e1c\u4eac IPLC", "🇯🇵 \u8282\u70b9", "[JP] Osaka")) {
            assertTrue(name, NodeCategories.matches(japan, name))
        }
        assertFalse(NodeCategories.matches(NodeCategory("c2", "Russia", "RU"), "RUSSELL"))
    }

    @Test fun aCategoryNamedAfterACountryNeedsNoFlag() {
        val uk = NodeCategory("c1", "\u82f1\u56fd")
        assertTrue(NodeCategories.matches(uk, "London-01"))
        assertEquals("GB", uk.country?.code)
    }

    @Test fun anyNameWorksAndAmbiguousNodesFallBack() {
        val games = NodeCategory("c1", "\u6e38\u620f")
        val hongKong = NodeCategory("c2", "\u6e2f\u7ebf", "HK")
        val s = state(games, hongKong)
        assertEquals("c1", NodeCategories.categoryFor(Profile(id = 1, name = "\u6e38\u620f\u4e13\u7ebf"), s))
        assertEquals("c2", NodeCategories.categoryFor(Profile(id = 2, name = "HK-BGP"), s))
        assertEquals(NodeCategories.FALLBACK_ID, NodeCategories.categoryFor(Profile(id = 3, name = "\u6e38\u620f HK"), s))
        assertEquals(NodeCategories.FALLBACK_ID, NodeCategories.categoryFor(Profile(id = 4, name = "Verizon"), s))
    }

    @Test fun manualPlacementWinsUntilItsCategoryIsGone() {
        val japan = NodeCategory("c1", "\u65e5\u672c", "JP")
        val work = NodeCategory("c2", "\u5de5\u4f5c")
        val tokyo = Profile(id = 7, name = "Tokyo-01")
        assertEquals("c2", NodeCategories.categoryFor(tokyo, state(japan, work, assignments = mapOf(7L to "c2"))))
        assertEquals("c1", NodeCategories.categoryFor(tokyo, state(japan, assignments = mapOf(7L to "c2"))))
        assertEquals(NodeCategories.FALLBACK_ID, NodeCategories.categoryFor(tokyo, state(japan, assignments = mapOf(7L to NodeCategories.FALLBACK_ID))))
    }

    @Test fun groupsKeepTheFallbackFirstAndUserOrderAfterIt() {
        val groups = NodeCategories.group(emptyList(), state(NodeCategory("b", "\u4e59"), NodeCategory("a", "\u7532")))
        assertEquals(listOf(NodeCategories.FALLBACK_ID, "b", "a"), groups.map { it.id })
        assertTrue(groups.first().fallback)
    }

    @Test fun storageRoundTripsAndRejectsBadLines() {
        val categories = listOf(NodeCategory("c1", "\u65e5\u672c", "JP"), NodeCategory("c2", "\u5de5\u4f5c"))
        assertEquals(categories, NodeCategories.decode(NodeCategories.encode(categories)))
        val decoded = NodeCategories.decode("c3\tA\tB\tZZ\n\tnameless\t\nlocal\tfallback\t\nc4\t \t\nc5\tok\tQQ")
        assertEquals(listOf("c3", "c5"), decoded.map { it.id })
        assertNull(decoded.last().flag)
        assertEquals("a b", NodeCategories.sanitizeName(" a\tb\n"))
    }

    @Test fun migrationKeepsOnlyManualChoices() {
        val migrated = NodeCategories.migrate(mapOf(1L to "ZZ", 2L to "GB", 3L to "custom:\u5de5\u4f5c", 4L to "GB", 5L to "nonsense"))
        assertEquals(listOf("m-GB", "m-custom-\u5de5\u4f5c"), migrated.categories.map { it.id })
        assertEquals("United Kingdom", migrated.categories.first().name)
        assertEquals("GB", migrated.categories.first().flag)
        assertEquals(mapOf(1L to NodeCategories.FALLBACK_ID, 2L to "m-GB", 3L to "m-custom-\u5de5\u4f5c", 4L to "m-GB"), migrated.assignments)
    }

    @Test fun flagsAreRegionalIndicatorPairs() {
        assertEquals("🇯🇵", flagEmoji("jp"))
        assertEquals("", flagEmoji("J"))
        assertEquals("", flagEmoji("1A"))
        assertEquals("JP", Countries.byName("\u65e5\u672c")?.code)
        assertEquals("JP", Countries.byName("Japan")?.code)
        assertNull(Countries.byName("\u5de5\u4f5c"))
    }

    @Test fun visibleNodesAlwaysIncludeTheSelectedOne() {
        val profiles = (1L..6L).map { Profile(id = it, name = "n$it") }
        val group = NodeGroup("c", "c", null, false, profiles)
        assertEquals(listOf(1L, 2L, 3L), visibleNodes(group, 2L).map { it.id })
        assertEquals(listOf(1L, 2L, 5L), visibleNodes(group, 5L).map { it.id })
    }

    @Test fun fourNodesStayWholeRatherThanFoldingOne() {
        val four = NodeGroup("c", "c", null, false, (1L..4L).map { Profile(id = it, name = "n$it") })
        assertEquals(listOf(1L, 2L, 3L, 4L), visibleNodes(four, 4L).map { it.id })
        assertEquals(false, foldsNodes(four))
        val five = NodeGroup("c", "c", null, false, (1L..5L).map { Profile(id = it, name = "n$it") })
        assertEquals(true, foldsNodes(five))
    }
}
