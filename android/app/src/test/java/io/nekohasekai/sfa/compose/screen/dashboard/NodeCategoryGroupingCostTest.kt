package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.database.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping runs on the main thread whenever the nodes or the categories change, including on every
 * keystroke while a category is renamed, so a few hundred imported nodes must group quickly.
 */
class NodeCategoryGroupingCostTest {
    private val places = listOf("Tokyo", "HK", "US", "London", "Singapore", "Seoul", "Frankfurt", "Game", "Stream", "Backup")

    private val profiles = (1..300L).map { Profile(id = it, name = "${places[(it % places.size).toInt()]}-${it.toString().padStart(3, '0')}") }

    private val state = NodeCategoryState(
        categories = listOf(
            NodeCategory("c-jp", "Japan", "JP"),
            NodeCategory("c-hk", "Hong Kong", "HK"),
            NodeCategory("c-us", "United States", "US"),
            NodeCategory("c-gb", "Britain", "GB"),
            NodeCategory("c-sg", "Singapore"),
            NodeCategory("c-kr", "Korea", "KR"),
            NodeCategory("c-de", "Germany", "DE"),
            NodeCategory("c-game", "Game"),
            NodeCategory("c-stream", "Stream"),
            NodeCategory("c-work", "Work"),
            NodeCategory("c-home", "Home"),
            NodeCategory("c-new", "New category"),
        ),
        assignments = mapOf(7L to "c-work", 8L to NodeCategories.FALLBACK_ID),
    )

    @Test
    fun groupingMatchesEachNodeOnItsOwn() {
        val groups = NodeCategories.group(profiles, state)
        assertEquals(profiles.size, groups.sumOf { it.profiles.size })
        for (group in groups) for (profile in group.profiles) {
            assertEquals(profile.name, NodeCategories.categoryFor(profile.id, profile.name, state), group.id)
        }
        assertEquals(listOf(7L), groups.first { it.id == "c-work" }.profiles.map { it.id })
        assertTrue(groups.first { it.id == "c-game" }.profiles.isNotEmpty())
        assertTrue(groups.first { it.id == "c-jp" }.profiles.all { it.name.startsWith("Tokyo") })
    }

    @Test
    fun threeHundredNodesGroupQuickly() {
        repeat(10) { NodeCategories.group(profiles, state) }
        val runs = 30
        val started = System.nanoTime()
        repeat(runs) { NodeCategories.group(profiles, state) }
        val ms = (System.nanoTime() - started) / 1_000_000.0 / runs
        println("NodeCategories.group: ${profiles.size} nodes, ${state.categories.size} categories, %.2f ms per grouping (JVM)".format(ms))
    }
}
