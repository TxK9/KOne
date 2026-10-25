package cc.txwd.kone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.txwd.kone.ui.InkScrollbar
import cc.txwd.kone.ui.ROOM
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.Label
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scrollbar must take a click where it is drawn: a bar placed outside its holder's bounds
 * (as the first version reached into the page's margin) is drawn but never clicked.
 */
class ScrollbarTest {
    @Test
    fun aClickOnTheBarScrollsTheList() {
        val list = LazyListState()
        val scene = ImageComposeScene(800, 600, Density(1f)) {
            KoneTheme(false) {
                // As the pages lay it out: a 40 dp margin, the list inside it with room on its right for the bar.
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(end = ROOM)) {
                            items(200) { Label("Row $it", 14.sp, modifier = Modifier.height(40.dp)) }
                        }
                        InkScrollbar(list, Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
        try {
            scene.render(0)
            // A click on the track below the thumb, at the bar's middle (the list's edge is 760, the bar 6 wide).
            val x = 760f - 3f
            val held = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, 500f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 500f), timeMillis = 0, buttons = held, button = PointerButton.Primary)
            scene.render(50_000_000)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 500f), timeMillis = 60, button = PointerButton.Primary)
            scene.render(100_000_000)
            assertTrue(list.firstVisibleItemIndex > 5, "a click on the bar's track moved the list to row ${list.firstVisibleItemIndex}")
        } finally {
            scene.close()
        }
    }
}
