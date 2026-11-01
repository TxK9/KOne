package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeListingTest {
    private data class Node(val id: Long, val name: String, val reading: NodeReading? = null)

    private fun sort(nodes: List<Node>, sort: NodeSort) =
        NodeListing.sort(nodes, sort, { it.name }) { NodeListing.rank(it.reading) }.map { it.id }

    @Test
    fun fastestFirstPutsRepliesByLatencyThenFailuresThenUntested() {
        val nodes = listOf(
            Node(1, "a"),
            Node(2, "b", NodeReading("Timed out", NodeHealth.Down, failed = true)),
            Node(3, "c", NodeReading("320 ms", NodeHealth.Good)),
            Node(4, "d", NodeReading("Timed out", NodeHealth.Slow, failed = true)),
            Node(5, "e", NodeReading("45 ms", NodeHealth.Good)),
            Node(6, "f", NodeReading("1203 ms", NodeHealth.Slow)),
        )
        assertEquals(listOf(5L, 3, 6, 4, 2, 1), sort(nodes, NodeSort.Latency))
    }

    @Test
    fun equalPlacesKeepTheSavedOrder() {
        val nodes = listOf(Node(1, "x"), Node(2, "y", NodeReading("90 ms", NodeHealth.Good)), Node(3, "z"), Node(4, "w", NodeReading("90 ms", NodeHealth.Good)))
        assertEquals(listOf(2L, 4, 1, 3), sort(nodes, NodeSort.Latency))
        assertEquals(listOf(1L, 2, 3, 4), sort(nodes, NodeSort.Saved))
    }

    @Test
    fun byNameComparesNumbersAsNumbersAndIgnoresFlagsInFront() {
        val nodes = listOf(
            Node(1, "Node 10"),
            Node(2, "🇯🇵 Tokyo"),
            Node(3, "node 2"),
            Node(4, "Amsterdam"),
            Node(5, "Node 02b"),
        )
        assertEquals(listOf(4L, 3, 5, 1, 2), sort(nodes, NodeSort.Name))
    }

    @Test
    fun naturalOrderHandlesLeadingZerosAndPrefixes() {
        val order = NodeListing.NaturalOrder
        assertTrue(order.compare("a2", "a10") < 0)
        assertTrue(order.compare("a007", "a8") < 0)
        assertTrue(order.compare("hk", "hk 1") < 0)
        assertEquals(0, order.compare("same 1", "same 1"))
    }

    @Test
    fun latencyComesFromTheLabelAndNotFromFailures() {
        assertEquals(245, NodeReading("245 ms", NodeHealth.Good).ms)
        assertNull(NodeReading("Connection refused", NodeHealth.Slow, failed = true).ms)
    }

    @Test
    fun searchMatchesEveryWordInTheNameOrCountry() {
        assertTrue(NodeListing.matches("Tokyo 01", "JP", "japan"))
        assertTrue(NodeListing.matches("Tokyo 01", "JP", "TOKYO 01"))
        assertTrue(NodeListing.matches("São Paulo", "BR", "sao"))
        assertTrue(NodeListing.matches("Anything", null, "   "))
        assertFalse(NodeListing.matches("Tokyo 01", "JP", "osaka"))
        assertFalse(NodeListing.matches("Tokyo 01", "JP", "tokyo 02"))
        assertFalse(NodeListing.matches("Tokyo 01", null, "japan"))
    }

    @Test
    fun duplicatesKeepOneOfEachAndNeverAManagedNode() {
        val prints = mapOf(1L to "a", 2L to "a", 3L to "b", 4L to "a", 5L to null, 6L to null, 7L to "c", 8L to "c")
        val ids = prints.keys.toList()
        // The first of each set is kept; nodes whose server cannot be told are never copies.
        assertEquals(setOf(2L, 4L, 8L), NodeListing.duplicates(ids, { prints[it] }, inUse = -1) { false })
        // The node in use is kept instead of the first.
        assertEquals(setOf(1L, 4L, 8L), NodeListing.duplicates(ids, { prints[it] }, inUse = 2) { false })
        // A managed node is kept before all others, and is never offered for deletion.
        assertEquals(setOf(1L, 2L, 7L), NodeListing.duplicates(ids, { prints[it] }, inUse = 2) { it == 4L || it == 8L })
    }

    @Test
    fun aLongListIsCheckedOnlyWhereItShows() {
        val short = (1L..NodeLatency.AUTO_ALL).toList()
        assertEquals(short.toSet(), NodeLatency.due(short, emptySet(), emptySet()))
        val long = (1L..300L).toList()
        assertEquals(setOf(5L, 6L, 120L), NodeLatency.due(long, setOf(5L, 6L, 999L), setOf(120L)))
    }
}
