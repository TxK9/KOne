package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperRotationTest {
    private val ids = listOf("a", "b", "c")

    @Test fun firstOpenAnchorsWithoutReplacingValidImage() {
        assertEquals(WallpaperRotation.Change("b", 1_000L), WallpaperRotation().check(ids, "b", 0, 1_000, 10))
        assertEquals("a", WallpaperRotation().check(ids, "custom", 1, 1_000, 10)?.id)
    }

    @Test fun waitsTenMinutesAndCannotRotateTwiceOnRecreation() {
        val clock = WallpaperRotation()
        val start = clock.check(ids, "a", 0, 1_000, 100)!!
        assertNull(clock.check(ids, "a", start.wallTime, 600_000, 600_099))
        val changed = clock.check(ids, "a", start.wallTime, 601_000, 600_100)!!
        assertEquals("b", changed.id)
        assertNull(clock.check(ids, "b", changed.wallTime, 601_000, 600_100))
    }

    @Test fun backgroundAndProcessRestoreAdvanceOnlyOneImage() {
        assertEquals("b", WallpaperRotation().check(ids, "a", 1_000, 9_001_000, 20)?.id)
        val clock = WallpaperRotation()
        assertNull(clock.check(ids, "a", 1_000, 501_000, 1_000_000))
        assertEquals("b", clock.check(ids, "a", 1_000, 601_000, 1_100_000)?.id)
    }

    @Test fun clockRollbackReanchorsAndLastImageWraps() {
        val clock = WallpaperRotation()
        assertEquals(WallpaperRotation.Change("c", 500), clock.check(ids, "c", 1_000, 500, 100))
        assertEquals("a", clock.check(ids, "c", 500, 600_500, 600_100)?.id)
    }
}
