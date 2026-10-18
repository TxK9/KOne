package cc.txwd.kone.app

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotkeyTest {
    /** On Windows: one holder at a time, and the key goes back when let go. */
    @Test
    fun theKeyIsHeldByOneAndGoesBackWhenLetGo() {
        assumeTrue(Places.windows, "Windows only")
        val first = Hotkey {}
        val second = Hotkey {}
        assumeTrue(first.take(true), "Windows here gives no hotkeys (another program, or no desktop)")
        try {
            assertFalse(second.take(true), "a key another holds is not given twice")
            assertTrue(first.take(false))
            assertTrue(second.take(true), "let go, the key can be taken again")
        } finally {
            first.take(false)
            second.take(false)
        }
    }
}
