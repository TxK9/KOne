package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ImportBloomPlacementTest {
    private fun direction(degrees: Double) = Math.toRadians(degrees).let { Offset(cos(it).toFloat(), sin(it).toFloat()) }

    @Test
    fun aLabelsNearEdgeSitsOnTheRimWhicheverWayItPoints() {
        // Beside the circle a label reaches by half its width, below it by half its height.
        assertEquals(40f, labelReach(direction(180.0), 80, 18), 0.01f)
        assertEquals(9f, labelReach(direction(90.0), 80, 18), 0.01f)
        // On a diagonal the box's corner, not its centre, is what must clear the circle.
        val diagonal = labelReach(direction(150.0), 80, 18)
        assertTrue(diagonal > 9f && diagonal < 40f)
        // A longer label, or a larger text size, moves further out instead of under the circle.
        assertTrue(labelReach(direction(180.0), 120, 24) > labelReach(direction(180.0), 80, 18))
    }

    @Test
    fun itemsStayOnANarrowScreen() {
        // 320 wide at density 1, 8 margin: a 54 circle centred 300 across moves in to end at 312.
        assertEquals(258, keptInside(at = 300f, size = 54, bound = 320, margin = 8f))
        assertEquals(8, keptInside(at = 10f, size = 54, bound = 320, margin = 8f))
        // Where it already fits it is left where it is.
        assertEquals(133, keptInside(at = 160f, size = 54, bound = 320, margin = 8f))
        // Something wider than the screen is centred as asked rather than squeezed.
        assertEquals(-40, keptInside(at = 160f, size = 400, bound = 320, margin = 8f))
    }
}
