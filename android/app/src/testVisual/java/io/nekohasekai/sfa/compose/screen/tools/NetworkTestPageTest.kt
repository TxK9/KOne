package io.nekohasekai.sfa.compose.screen.tools

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.nekohasekai.sfa.compose.theme.BackdropContext
import io.nekohasekai.sfa.compose.theme.LocalBackdrop
import io.nekohasekai.sfa.compose.theme.WallpaperTheme
import io.nekohasekai.sfa.connection.CallingOptions
import io.nekohasekai.sfa.udp.UdpCheckState
import io.nekohasekai.sfa.udp.UdpMeasurement
import io.nekohasekai.sfa.udp.UdpPhase
import io.nekohasekai.sfa.udp.UdpReply
import io.nekohasekai.sfa.udp.UdpTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
class NetworkTestPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingToolsDisposesThePreviousToolAndLeavingDisposesTheLastOne() {
        var selected by mutableStateOf(NetworkTestTab.Speed)
        var visible by mutableStateOf(true)
        val disposed = mutableListOf<NetworkTestTab>()
        compose.setContent {
            MaterialTheme {
                if (visible) {
                    NetworkTestPage(selected, { selected = it }, Modifier.fillMaxSize()) { tab ->
                        DisposableEffect(tab) { onDispose { disposed += tab } }
                        Text(when (tab) {
                            NetworkTestTab.Speed -> "Speed test content"
                            NetworkTestTab.Udp -> "UDP content"
                            NetworkTestTab.Check -> "Check content"
                        })
                    }
                }
            }
        }
        compose.onNodeWithText("Speed").assertIsSelected()
        compose.onNodeWithText("Speed test content").assertIsDisplayed()
        compose.onNodeWithText("UDP content").assertDoesNotExist()
        compose.onNodeWithText("UDP").performClick().assertIsSelected()
        compose.onNodeWithText("Speed test content").assertDoesNotExist()
        compose.onNodeWithText("UDP content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(NetworkTestTab.Speed), disposed) }
        compose.onNodeWithText("Setup").performClick().assertIsSelected()
        compose.onNodeWithText("UDP content").assertDoesNotExist()
        compose.onNodeWithText("Speed test content").assertDoesNotExist()
        compose.onNodeWithText("Check content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(NetworkTestTab.Speed, NetworkTestTab.Udp), disposed) }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertEquals(listOf(NetworkTestTab.Speed, NetworkTestTab.Udp, NetworkTestTab.Check), disposed) }
    }

    @Test fun restoredUdpEntryStartsOnTheUdpTab() {
        compose.setContent {
            MaterialTheme {
                NetworkTestPage(NetworkTestTab.Udp, {}, Modifier.fillMaxSize()) { Text(it.name + " content") }
            }
        }
        compose.onNodeWithText("UDP").assertIsSelected()
        compose.onNodeWithText("Udp content").assertIsDisplayed()
        compose.onNodeWithText("Speed content").assertDoesNotExist()
    }

    @Test fun mergedUdpResultAndCallingControlsFitNarrowLightAndDarkScreens() {
        var dark by mutableStateOf(false)
        var calling by mutableStateOf(CallingOptions())
        var settingsOpened = 0
        val target = UdpTarget(7, "Current node", "proxy", 3, 40, false)
        val result = UdpCheckState(
            target, UdpPhase.Passed,
            UdpMeasurement(true, UdpReply("203.0.113.10:3478", 123), 1),
        )
        compose.setContent {
            val wallpaper = WallpaperTheme(dark)
            val paper = Color(wallpaper.readingSurface)
            val ink = Color(wallpaper.ink)
            val colors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
                surface = paper, onSurface = ink, primary = ink, onPrimary = paper,
                background = paper, onBackground = ink,
            )
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(
                    LocalBackdrop provides BackdropContext(null, Rect(0f, 0f, 640f, 1440f), theme = wallpaper),
                    LocalContentColor provides ink,
                ) {
                    Surface(Modifier.fillMaxSize(), color = paper) {
                        Column(Modifier.fillMaxSize()) {
                            NetworkTestTopBar(onBack = {})
                            Box(Modifier.weight(1f)) {
                                NetworkTestPage(NetworkTestTab.Udp, {}, Modifier.fillMaxSize()) {
                                    UdpTestPanel(
                                        "Current node", result, "Connect to a node first", {}, {}, { settingsOpened++ },
                                        connectionSettings = {
                                            CallingOptionsEditor(calling, false, "") { calling = it }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            compose.onNodeWithText("Via Current node").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Network tests").assertIsDisplayed()
            compose.onNodeWithText("UDP").assertIsSelected()
            compose.onNodeWithText("Available").assertIsDisplayed()
            compose.onNodeWithText("123").assertIsDisplayed()
            savePreview("network-tools-${if (isDark) "dark" else "light"}-udp.png")
            compose.onNodeWithText("Wi-Fi Calling").performScrollTo().performClick()
            compose.onNodeWithText("Call compatibility").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open system settings").performScrollTo().assertIsDisplayed().performClick()
            savePreview("network-tools-${if (isDark) "dark" else "light"}-calling.png")
            compose.onNodeWithText("Wi-Fi Calling").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(2, settingsOpened) }
    }

    private fun savePreview(name: String) {
        val output = File(System.getProperty("knine.previewDir"), name)
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
