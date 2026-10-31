package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row recomposes when a state it read changes. These record what a row showing one node reads
 * and what a new result writes, the way the frame clock sees them, so a long list can be checked
 * for how many rows each result wakes.
 */
class NodeLatencyInvalidationTest {
    private val used = mutableListOf<Long>()

    private fun node(id: Long): Long = id.also { used += it }

    @After
    fun clear() {
        used.forEach(NodeLatency::forget)
        Snapshot.sendApplyNotifications()
    }

    /** The state objects a row showing [id] reads. */
    private fun readsOf(id: Long): Set<Any> {
        val reads = HashSet<Any>()
        val snapshot = Snapshot.takeSnapshot { reads += it }
        try {
            snapshot.enter { NodeLatency[id] }
        } finally {
            snapshot.dispose()
        }
        return reads
    }

    /** The state objects [write] changes once applied. */
    private fun changedBy(write: () -> Unit): Set<Any> {
        Snapshot.sendApplyNotifications()
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        try {
            write()
            Snapshot.sendApplyNotifications()
        } finally {
            handle.dispose()
        }
        return changed
    }

    private fun wakes(row: Set<Any>, changed: Set<Any>) = row.any { it in changed }

    @Test
    fun aResultWakesOnlyItsOwnRow() {
        val a = node(9_001)
        val b = node(9_002)
        NodeLatency.recordTcp(a, LatencySample(80))
        NodeLatency.recordTcp(b, LatencySample(90))
        val rowA = readsOf(a)
        val rowB = readsOf(b)
        val changed = changedBy { NodeLatency.recordTcp(b, LatencySample(140)) }
        assertTrue(wakes(rowB, changed))
        assertTrue("a result for b recomposed a's row", !wakes(rowA, changed))
        assertEquals("140 ms", NodeLatency[b]?.label)
    }

    @Test
    fun anUnchangedVerdictWakesNoRow() {
        val a = node(9_011)
        NodeLatency.recordTcp(a, LatencySample(80))
        val row = readsOf(a)
        val changed = changedBy { NodeLatency.recordTcp(a, LatencySample(80)) }
        assertTrue("the same 80 ms recomposed the row", !wakes(row, changed))
    }

    @Test
    fun aRowLearnsOfItsFirstReading() {
        val a = node(9_021)
        val row = readsOf(a)
        assertNull(NodeLatency[a])
        val changed = changedBy { NodeLatency.recordTcp(a, LatencySample(60)) }
        assertTrue(wakes(row, changed))
        assertEquals("60 ms", NodeLatency[a]?.label)
    }

    @Test
    fun forgettingAndReturningReachTheRow() {
        val a = node(9_031)
        NodeLatency.recordTcp(a, LatencySample(60))
        val row = readsOf(a)
        assertTrue(wakes(row, changedBy { NodeLatency.forget(a) }))
        assertNull(NodeLatency[a])
        val empty = readsOf(a)
        assertTrue(wakes(empty, changedBy { NodeLatency.recordTcp(a, LatencySample(70)) }))
        assertNotNull(NodeLatency[a])
    }

    @Test
    fun theProxyReadingStillWakesItsRow() {
        val a = node(9_041)
        NodeLatency.recordTcp(a, LatencySample(60))
        val row = readsOf(a)
        try {
            assertTrue(wakes(row, changedBy {
                NodeLatency.useProxyReadings(a)
                NodeLatency.recordProxy(a, LatencySample(null, "Proxy unreachable"))
            }))
            assertEquals(true, NodeLatency[a]?.failed)
        } finally {
            NodeLatency.useProxyReadings(null)
        }
        assertEquals("60 ms", NodeLatency[a]?.label)
    }

    /** One sweep over a long list: every node reports once, and each report should wake one row, not all. */
    @Test
    fun aSweepOverManyNodesWakesEachRowOnce() {
        val ids = (0 until 300L).map { node(9_100 + it) }
        ids.forEach { NodeLatency.recordTcp(it, LatencySample(100)) }
        Snapshot.sendApplyNotifications()
        val rows = ids.associateWith(::readsOf)
        var woken = 0
        ids.forEachIndexed { i, id ->
            val changed = changedBy { NodeLatency.recordTcp(id, LatencySample(100 + i % 7 * 50)) }
            woken += rows.values.count { wakes(it, changed) }
        }
        println("NodeLatency sweep: ${ids.size} results woke $woken row compositions")
        assertTrue("one sweep woke $woken rows", woken <= ids.size)
    }

    /** The first sweep after a list appears, before any node has a reading. */
    @Test
    fun aFirstSweepWakesEachRowAtMostTwice() {
        val ids = (0 until 300L).map { node(9_500 + it) }
        val rows = ids.associateWith(::readsOf)
        var woken = rows.values.count { wakes(it, changedBy { NodeLatency.watch(ids) }) }
        val watched = ids.associateWith(::readsOf)
        ids.forEach { id ->
            val changed = changedBy { NodeLatency.recordTcp(id, LatencySample(120)) }
            woken += watched.values.count { wakes(it, changed) }
        }
        println("NodeLatency first sweep: ${ids.size} results woke $woken row compositions")
        assertTrue("the first sweep woke $woken rows", woken <= 2 * ids.size)
    }
}
