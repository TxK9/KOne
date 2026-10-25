package cc.txwd.kone.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SweepsTest {
    @Test
    fun nodesAreTestedOftenOnlyWhileTheirDelaysCanBeSeen() {
        assertEquals(60_000L, Sweeps.every(inSight = true, nodes = 12))
        assertEquals(600_000L, Sweeps.every(inSight = false, nodes = 12), "minimised or in the tray: every ten minutes")
        assertEquals(600_000L, Sweeps.every(inSight = true, nodes = 200), "a long list costs more each sweep")
        assertEquals(1_800_000L, Sweeps.every(inSight = false, nodes = 200))
    }

    @Test
    fun comingBackToStaleDelaysTestsThemAtOnce() {
        assertTrue(Sweeps.stale(age = 5 * 60_000L, nodes = 12), "five minutes away: tested again on return")
        assertFalse(Sweeps.stale(age = 20_000L, nodes = 12), "a glance away: the delays still stand")
        assertFalse(Sweeps.stale(age = 5 * 60_000L, nodes = 200), "a long list waits for its own ten minutes")
    }

    @Test
    fun theNodeInUseFailingTestsTheOthersNow() {
        assertTrue(Sweeps.afterFailure(failing = 1, age = 9 * 60_000L), "a failover should pick a node that answers now")
        assertFalse(Sweeps.afterFailure(failing = 0, age = 9 * 60_000L), "nothing failing: no extra sweep")
        assertFalse(Sweeps.afterFailure(failing = 2, age = 30_000L), "swept half a minute ago: those results stand")
    }
}
