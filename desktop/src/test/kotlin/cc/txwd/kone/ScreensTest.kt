package cc.txwd.kone

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cc.txwd.kone.ink.InkGallery
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.DashboardActions
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.DashboardView
import cc.txwd.kone.ui.Frame
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.KeyView
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.Page
import cc.txwd.kone.ui.SettingsActions
import cc.txwd.kone.ui.SettingsState
import cc.txwd.kone.ui.SettingsView
import cc.txwd.kone.ui.NodeWeather
import cc.txwd.kone.ui.TitleBar
import cc.txwd.kone.ui.TrayIcon
import cc.txwd.kone.ui.TrayPanel
import cc.txwd.kone.ui.UpdateState
import cc.txwd.kone.ui.WithRail
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the pages at the preview's 1280 by 800, light and dark, without a window, and leaves
 * the PNGs in build/screens for comparing with docs/windows/preview.html. The nodes, delays and
 * the exit address are the preview's examples, not anyone's real servers.
 */
class ScreensTest {
    private val sample = DashboardState(
        categories = listOf("\u7f8e\u56fd", "\u65e5\u672c", "\u65b0\u52a0\u5761"),
        nodes = listOf(
            Node("us1", "\u7f8e\u56fd", "US", "\u7f8e\u56fd", 245),
            Node("us2", "United States 2", "US", "\u7f8e\u56fd", 312),
            Node("jp1", "\u65e5\u672c", "JP", "\u65e5\u672c", 186),
            Node("sg1", "\u65b0\u52a0\u5761", "SG", "\u65b0\u52a0\u5761", 1040),
        ),
        tab = "\u7f8e\u56fd",
        selected = "us1",
        canImport = true,
    )
    private val online = sample.copy(connection = Connection.Online, exit = "203.0.113.24", home = true, downKbps = 18.6, upKbps = 1.2, seconds = 754)

    private val out = File(System.getProperty("kone.screens") ?: "build/screens").apply { mkdirs() }

    private fun shoot(name: String, dark: Boolean, width: Int = 1280, height: Int = 800, framed: Boolean = true, pointer: Offset? = null, content: @Composable () -> Unit) {
        runBlocking { InkGallery.paint(dark) }
        val scene = ImageComposeScene(width, height, Density(1f)) {
            KoneTheme(dark) { if (framed) Frame({ TitleBar() }) { content() } else content() }
        }
        try {
            // Twice: the first frame lays out, the second has the paintings in place.
            scene.render(0)
            // Where the mouse rests, for the hover wash.
            pointer?.let { scene.sendPointerEvent(PointerEventType.Move, it) }
            val image = scene.render(16_000_000)
            val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "could not encode $name" }
            val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 4_000, "$file looks empty")
        } finally {
            scene.close()
        }
    }

    @Test
    fun dashboard() {
        for (dark in listOf(false, true)) {
            shoot("dashboard", dark) { WithRail(Page.Dashboard, {}) { DashboardView(sample, DashboardActions(), false, {}, moving = false) } }
            shoot("dashboard-online", dark) { WithRail(Page.Dashboard, {}) { DashboardView(online, DashboardActions(), false, {}, moving = false) } }
        }
        // The smallest the window goes.
        shoot("dashboard-smallest", dark = false, width = 960, height = 620) {
            WithRail(Page.Dashboard, {}) { DashboardView(online, DashboardActions(), false, {}, moving = false) }
        }
    }

    /** The mouse resting on a node: the hand and a faint wash of ink under it. */
    @Test
    fun hover() {
        for (dark in listOf(false, true)) {
            shoot("dashboard-hover", dark, pointer = Offset(390f, 672f)) {
                WithRail(Page.Dashboard, {}) { DashboardView(sample, DashboardActions(), false, {}, moving = false) }
            }
        }
    }

    /** Settings with a newer KOne out, and Start with Windows off as it starts. */
    @Test
    fun settings() {
        val state = SettingsState(Appearance.System, autostart = false, version = "1.0.6", update = UpdateState.Available("1.0.7"))
        for (dark in listOf(false, true)) {
            shoot("settings", dark) {
                WithRail(Page.Settings, {}, listOf(Page.Dashboard, Page.Log, Page.Settings), marked = setOf(Page.Settings)) {
                    SettingsView(state, SettingsActions())
                }
            }
        }
    }

    /** The tray panel in each weather: sun, mist and storm while connected, moon when not. */
    @Test
    fun trayPanel() {
        val weathers = listOf("qing" to 245, "wu" to 1180, "duan" to null)
        for (dark in listOf(false, true)) {
            for ((name, delay) in weathers) {
                val state = online.copy(nodes = online.nodes.map { if (it.id == "us1") it.copy(delay = delay) else it })
                shoot("tray-$name", dark, 360, 420, framed = false) { TrayPanel(state, {}, {}, {}, moving = false) }
            }
            shoot("tray-xie", dark, 360, 420, framed = false) { TrayPanel(sample, {}, {}, {}, moving = false) }
        }
    }

    /** The tray icon on a dark and a light taskbar, in all four weathers. */
    @Test
    fun trayIcons() {
        for (dark in listOf(true, false)) {
            val sheet = java.awt.image.BufferedImage(4 * 56, 56, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = sheet.createGraphics()
            g.color = java.awt.Color(if (dark) 0x202020 else 0xE6E8EB)
            g.fillRect(0, 0, sheet.width, sheet.height)
            NodeWeather.entries.forEachIndexed { index, weather -> g.drawImage(TrayIcon.image(weather, dark, 40), index * 56 + 8, 8, null) }
            g.dispose()
            javax.imageio.ImageIO.write(sheet, "png", File(out, "tray-icons-${if (dark) "dark" else "light"}.png"))
        }
    }

    @Test
    fun key() {
        for (dark in listOf(false, true)) {
            shoot("key", dark) { KeyView(KeyState(), {}, {}, {}, moving = false) }
            shoot("key-filled", dark) { KeyView(KeyState("kone-7q4m-x2p9-hd3k"), {}, {}, {}, moving = false) }
        }
    }
}
