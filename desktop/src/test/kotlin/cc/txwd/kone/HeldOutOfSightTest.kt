package cc.txwd.kone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A minimised window draws a frame for each change it is shown; the pages are held out of sight instead. */
class HeldOutOfSightTest {
    @Test
    fun aChangeOutOfSightAsksForNoFrameAndShowsOnReturn() {
        val inSight = mutableStateOf(true)
        val figures = mutableStateOf(1)
        var shown = 0
        val scene = ImageComposeScene(64, 64) {
            val held by heldOutOfSight({ inSight.value }) { figures.value }
            shown = held
            Box(Modifier.size(8.dp))
        }
        try {
            scene.render()
            assertEquals(1, shown)
            inSight.value = false
            Snapshot.sendApplyNotifications()
            scene.render()

            figures.value = 2
            Snapshot.sendApplyNotifications()
            assertFalse(scene.hasInvalidations(), "a change out of sight asked for a frame")
            scene.render()
            assertEquals(1, shown)

            inSight.value = true
            Snapshot.sendApplyNotifications()
            assertTrue(scene.hasInvalidations())
            scene.render()
            assertEquals(2, shown)
        } finally {
            scene.close()
        }
    }

    @Test
    fun readDirectlyTheSameChangeAsksForAFrame() {
        // The measure the first test relies on: without the hold, the change is seen.
        val figures = mutableStateOf(1)
        var shown = 0
        val scene = ImageComposeScene(64, 64) {
            shown = figures.value
            Box(Modifier.size(8.dp))
        }
        try {
            scene.render()
            figures.value = 2
            Snapshot.sendApplyNotifications()
            assertTrue(scene.hasInvalidations())
            scene.render()
            assertEquals(2, shown)
        } finally {
            scene.close()
        }
    }
}
