package io.nekohasekai.sfa.compose.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UptimeTickTest {
    private val start = 1_761_400_000_123L

    @Test fun waitsExactlyUntilTheNextWholeSecondOfUptime() {
        assertEquals(1_000L, untilNextSecond(start, start))
        assertEquals(1_000L, untilNextSecond(start + 3_000, start))
        assertEquals(1L, untilNextSecond(start + 3_999, start))
        assertEquals(750L, untilNextSecond(start + 7_250, start))
    }

    @Test fun landingOnTheBoundaryShowsTheNewSecondWithoutSkipping() {
        var now = start + 2_400
        val shown = mutableListOf<Long>()
        repeat(5) {
            now += untilNextSecond(now, start)
            shown += (now - start) / 1_000
        }
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), shown)
    }

    @Test fun aStartTimeAheadOfThePhoneClockStillWaitsLessThanASecond() {
        val wait = untilNextSecond(start - 1_300, start)
        assertTrue(wait in 1L..1_000L)
        assertEquals(300L, wait)
    }
}
