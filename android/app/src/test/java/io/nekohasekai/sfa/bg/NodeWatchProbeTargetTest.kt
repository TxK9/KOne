package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeWatchProbeTargetTest {
    private fun group(tag: String, selected: String, vararg items: String, type: String = "selector") =
        Group(tag, type, type, type == "selector", selected, false, items.map { GroupItem(it, "vless", "VLESS", 0L, 0) })

    @Test fun aSingleNodeIsTestedByItsOwnTag() {
        val proxy = group("proxy", "node", "node")
        assertEquals("node", probeTarget(listOf(proxy), proxy))
    }

    @Test fun aGroupOfManyNodesTestsOnlyTheSelectedOne() {
        val proxy = group("proxy", "node-b", "node-a", "node-b", "node-c")
        assertEquals("node-b", probeTarget(listOf(proxy), proxy))
    }

    @Test fun anAutomaticChoiceIsFollowedToTheNodeItPicked() {
        val proxy = group("proxy", "Automatic", "Automatic", "node-a", "node-b")
        val automatic = group("Automatic", "node-b", "node-a", "node-b", type = "urltest")
        assertEquals("node-b", probeTarget(listOf(proxy, automatic), proxy))
    }

    @Test fun nestedChoicesAreFollowedAllTheWayDown() {
        val proxy = group("proxy", "Region", "Region", "node-a")
        val region = group("Region", "Automatic", "Automatic", "node-c")
        val automatic = group("Automatic", "node-d", "node-d", "node-e", type = "urltest")
        assertEquals("node-d", probeTarget(listOf(proxy, region, automatic), proxy))
    }

    @Test fun noSelectionLeavesTheCallerToFallBack() {
        val proxy = group("proxy", "", "node-a")
        assertNull(probeTarget(listOf(proxy), proxy))
        val nested = group("proxy", "Automatic", "Automatic")
        val automatic = group("Automatic", "", "node-a", type = "urltest")
        assertNull(probeTarget(listOf(nested, automatic), nested))
    }

    @Test fun aLoopEndsInsteadOfSpinning() {
        val proxy = group("proxy", "left", "left")
        val left = group("left", "right", "right")
        val right = group("right", "left", "left")
        assertNull(probeTarget(listOf(proxy, left, right), proxy))
        val self = group("proxy", "proxy", "proxy")
        assertNull(probeTarget(listOf(self), self))
    }
}
