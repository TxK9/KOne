package cc.txwd.kone

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import cc.txwd.kone.ink.InkGallery
import cc.txwd.kone.ui.DashboardActions
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.DashboardView
import cc.txwd.kone.ui.Frame
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.Page
import cc.txwd.kone.ui.TitleBar
import cc.txwd.kone.ui.WithRail
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * KOne with the keyboard alone: Tab goes through the rail and the page (not the window's own
 * buttons, as in Windows' own title bars), Enter presses what Tab reached, and the place is
 * outlined. The outlined frames are left in build/screens for review.
 */
class KeyboardTest {
    private val out = File(System.getProperty("kone.screens") ?: "build/screens").apply { mkdirs() }
    private val state = DashboardState(
        categories = listOf("United States", "Japan"),
        nodes = listOf(Node("us1", "United States", "US", "United States", 245), Node("jp1", "Japan", "JP", "Japan", 186)),
        tab = "United States", selected = "us1",
    )

    // Compose's own key event for tests, as a window would send it.
    @OptIn(InternalComposeUiApi::class)
    private fun ImageComposeScene.press(key: Key, shift: Boolean = false) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            sendKeyEvent(KeyEvent(key, type, isShiftPressed = shift))
            render(0)
        }
    }

    private fun ImageComposeScene.save(name: String) {
        val png = checkNotNull(render(16_000_000).encodeToData(EncodedImageFormat.PNG))
        File(out, "$name.png").writeBytes(png.bytes)
    }

    @Test
    fun tabReachesTheRailAndThePageAndEnterPresses() {
        runBlocking { InkGallery.paint(false) }
        val picked = mutableListOf<Page>()
        var window = 0
        val scene = ImageComposeScene(1280, 800, Density(1f)) {
            KoneTheme(false) {
                Frame({ TitleBar(onMinimize = { window++ }, onMaximize = { window++ }, onClose = { window++ }) }) {
                    WithRail(Page.Dashboard, { picked += it }) { DashboardView(state, DashboardActions(), false, {}, moving = false) }
                }
            }
        }
        try {
            scene.render(0)
            // The first Tab reaches the rail's first page, not Minimise.
            scene.press(Key.Tab)
            scene.save("keyboard-rail-light")
            scene.press(Key.Enter)
            assertEquals(listOf(Page.Dashboard), picked)
            scene.press(Key.Tab)
            scene.press(Key.Enter)
            assertEquals(listOf(Page.Dashboard, Page.Nodes), picked)
            // Shift+Tab goes back.
            scene.press(Key.Tab, shift = true)
            scene.press(Key.Enter)
            assertEquals(listOf(Page.Dashboard, Page.Nodes, Page.Dashboard), picked)
            // On past the rail into the page, and all the way round, never onto the window's buttons.
            repeat(Page.entries.size) { scene.press(Key.Tab) }
            scene.save("keyboard-page-light")
            repeat(40) { scene.press(Key.Tab); scene.press(Key.Enter) }
            assertEquals(0, window, "Tab reached the window's own buttons")
        } finally {
            scene.close()
        }
    }

    @Test
    fun aMouseClickLeavesNoOutline() {
        runBlocking { InkGallery.paint(false) }
        val picked = mutableListOf<Page>()
        val scene = ImageComposeScene(1280, 800, Density(1f)) {
            KoneTheme(false) {
                Frame({ TitleBar() }) {
                    WithRail(Page.Dashboard, { picked += it }) { DashboardView(state, DashboardActions(), false, {}, moving = false) }
                }
            }
        }
        // The left edge of the rail's second item (Nodes), where an outline would be drawn.
        fun edge(): IntArray {
            val pixels = scene.render(16_000_000).toComposeImageBitmap()
            return IntArray(40).also { pixels.readPixels(it, startX = 9, startY = 132, width = 1, height = 40) }
        }
        try {
            scene.render(0)
            scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 400f))
            val before = edge()
            scene.sendPointerEvent(PointerEventType.Move, Offset(42f, 152f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(42f, 152f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(42f, 152f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 400f))
            assertEquals(listOf(Page.Nodes), picked)
            assertContentEquals(before, edge(), "a click left the keyboard's outline")
            // The same place reached again with the keyboard is outlined: the check above can see it.
            scene.press(Key.Tab, shift = true)
            scene.press(Key.Tab)
            assertFalse(before.contentEquals(edge()), "Tab's outline was not drawn")
        } finally {
            scene.close()
        }
    }
}
