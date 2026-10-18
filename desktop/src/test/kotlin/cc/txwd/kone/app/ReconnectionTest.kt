package cc.txwd.kone.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconnectionTest {
    @Test
    fun aConnectionThatHeldIsTriedAgainLaterEachTime() {
        assertEquals(listOf(1_000L, 3_000L, 10_000L, 30_000L, 60_000L), (0 until 5).map { Reconnection.after("exited 1", held = true, attempts = it) })
        assertNull(Reconnection.after("exited 1", held = true, attempts = 5), "five tries, then it says so")
    }

    @Test
    fun theServiceGoingAwayOrTheComputerWakingAreTriedAgain() {
        for (end in listOf("exited", "no service", "no answer", "restart", "failed")) {
            assertEquals(1_000L, Reconnection.after(end, held = true, attempts = 0), end)
        }
    }

    @Test
    fun whatWillNotPassOrWasAskedForIsNot() {
        for (end in listOf("stopped", "config_invalid", "busy", "untrusted")) {
            assertNull(Reconnection.after(end, held = true, attempts = 0), end)
        }
        assertNull(Reconnection.after("exited 1", held = false, attempts = 0), "a connection that never came up says why at once")
    }
}
