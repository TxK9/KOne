package cc.txwd.kone

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cc.txwd.kone.i18n.Language
import cc.txwd.kone.i18n.Strings
import cc.txwd.kone.ink.InkGallery
import cc.txwd.kone.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real Compose text and layout at the three Windows display scales; synthetic data only. */
class ChineseScreensTest {
    private val ui = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val work = FutureTask<Unit> { block() }
        SwingUtilities.invokeAndWait(work)
        work.get()
    }

    @Test fun simplifiedChinesePagesAtAllDisplayScales() {
        val original = Strings.language
        try {
            Strings.language = Language.SimplifiedChinese
            for (dark in listOf(false, true)) for (scale in listOf(1f, 1.5f, 2f)) {
                screenshot("key", dark, scale) { KeyView(KeyState(), {}, {}, {}, moving = false) }
                screenshot("dashboard", dark, scale) {
                    WithRail(Page.Dashboard, {}) {
                        DashboardView(DashboardState(categories = listOf("United States"), nodes = listOf(Node("example", "Example node", "US", "United States", 42)), tab = "United States", selected = "example"), DashboardActions(), false, {}, moving = false)
                    }
                }
                screenshot("settings", dark, scale) {
                    WithRail(Page.Settings, {}) {
                        SettingsView(SettingsState(Appearance.System, autostart = false, hotkey = true, version = "1.0.70", update = UpdateState.Available("1.0.71"), language = Language.SimplifiedChinese), SettingsActions())
                    }
                }
            }
        } finally { Strings.language = original }
    }

    private fun screenshot(name: String, dark: Boolean, scale: Float, content: @Composable () -> Unit) = onUi {
        runBlocking { InkGallery.paint(dark) }
        val scene = ImageComposeScene((1280 * scale).toInt(), (800 * scale).toInt(), Density(scale), coroutineContext = ui) {
            KoneTheme(dark) { Frame({ TitleBar(onLanguage = {}) }) { content() } }
        }
        try {
            for (time in listOf(0L, 16L, 200L, 500L)) scene.render(time * 1_000_000)
            val image = scene.render(1_000_000_000)
            val directory = File(System.getProperty("kone.screens"), "zh-CN").apply { mkdirs() }
            val file = File(directory, "$name-${if (dark) "dark" else "light"}-${(scale * 100).toInt()}.png")
            file.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            assertTrue(file.length() > 8000, "Localized page did not render: $file")
        } finally { scene.close() }
    }
}
