package io.nekohasekai.sfa.compose.screen.tools

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.nekohasekai.sfa.compose.theme.BackdropContext
import io.nekohasekai.sfa.compose.theme.LocalBackdrop
import io.nekohasekai.sfa.compose.theme.WallpaperTheme
import io.nekohasekai.sfa.connection.CallingOptions
import io.nekohasekai.sfa.connection.ConnectionCheckInput
import io.nekohasekai.sfa.connection.ConnectionCheckId
import io.nekohasekai.sfa.connection.ConnectionCheckItem
import io.nekohasekai.sfa.connection.ConnectionCheckLevel
import io.nekohasekai.sfa.connection.ConnectionCheckMode
import io.nekohasekai.sfa.connection.ConnectionCheckReport
import io.nekohasekai.sfa.connection.ConnectionChecks
import io.nekohasekai.sfa.connection.ConnectionCompatibility
import io.nekohasekai.sfa.connection.GlobalRoutingPolicy
import io.nekohasekai.sfa.connection.VpnOwnership
import io.nekohasekai.sfa.connection.VpnProtectionReadability
import io.nekohasekai.sfa.connection.VpnProtectionSnapshot
import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.json.JSONObject
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
class ConnectionCheckPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localCheckRequiresAnExplicitClickAndScopeIsCollapsed() {
        var checks = 0
        var settings = 0
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ConnectionCheckPanel(null, true, { checks++ }, { settings++ }) } } }
        compose.onNodeWithText("Not checked").assertIsDisplayed()
        compose.onNodeWithText("Check").performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(0, settings) }
        compose.onNodeWithText("Actual DNS and IPv6 egress, network changes, other apps, secondary profiles and system call routes require separate device tests.").assertDoesNotExist()
        compose.onNodeWithText("Test scope").performScrollTo().performClick()
        compose.onNodeWithText("Actual DNS and IPv6 egress, network changes, other apps, secondary profiles and system call routes require separate device tests.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open system VPN settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun aChangedConnectionShowsUnconfirmedRowsAndPausedUiCannotCheck() {
        var enabled by mutableStateOf(true)
        var report by mutableStateOf(ConnectionCheckReport(listOf(ConnectionCheckItem(ConnectionCheckId.Exceptions, ConnectionCheckLevel.Information, "Number of active explicitly direct apps.", 2))))
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ConnectionCheckPanel(report, enabled, {}, {}) } } }
        compose.onNodeWithText("2 apps").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { report = ConnectionChecks.changed(); enabled = false }
        compose.onNodeWithText("2 apps").assertDoesNotExist()
        compose.onNodeWithText("Check again").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Open system VPN settings").performScrollTo().assertIsNotEnabled()
    }

    @Test fun integratedConnectionCheckFitsNarrowLightAndDarkThemes() {
        var dark by mutableStateOf(false)
        var checks = 0
        var settings = 0
        val report = fixtureReport()
        assertEquals(2, report.items.single { it.id == ConnectionCheckId.Exceptions }.count)
        assertEquals(ConnectionCheckLevel.Configured, report.items.single { it.id == ConnectionCheckId.Dns }.level)
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
                                key(dark) {
                                    NetworkTestPage(NetworkTestTab.Check, {}, Modifier.fillMaxSize()) {
                                        ConnectionCheckPanel(report, true, { checks++ }, { settings++ })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            val tone = if (isDark) "dark" else "light"
            compose.runOnIdle { dark = isDark }
            compose.onNodeWithText("Network tests").assertIsDisplayed()
            compose.onNode(hasText("Setup") and hasClickAction()).assertIsSelected()
            compose.onNodeWithText("Check again").performScrollTo().performClick()
            compose.onNodeWithText("Active VPN").performScrollTo().assertIsDisplayed()
            savePreview("connection-check-$tone.png")

            compose.onNodeWithText("IPv4 / IPv6").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("App DNS").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Direct exceptions").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("2 apps").performScrollTo().assertIsDisplayed()
            savePreview("connection-check-$tone-routes.png")

            val calling = report.items.single { it.id == ConnectionCheckId.Calling }.detail
            // Information rows keep their reason folded until tapped.
            compose.onNodeWithText(calling).assertDoesNotExist()
            compose.onNodeWithText("Wi-Fi Calling").performScrollTo().performClick()
            compose.onNodeWithText(calling).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Always-on VPN").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Block connections without VPN").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("The system setting is disabled. Normal network access may resume when the VPN stops.").performScrollTo().assertIsDisplayed()
            savePreview("connection-check-$tone-protection.png")

            compose.onNodeWithText("Test scope").performScrollTo().performClick()
            compose.onNodeWithText("Actual DNS and IPv6 egress, network changes, other apps, secondary profiles and system call routes require separate device tests.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open system VPN settings").performScrollTo().performClick()
            savePreview("connection-check-$tone-scope.png")
        }
        compose.runOnIdle { assertEquals(2, checks); assertEquals(2, settings) }
    }

    /** Uses the real pure model with synthetic accepted input; no system or native calls. */
    private fun fixtureReport(): ConnectionCheckReport {
        val saved = PairedProfile.fromLink(
            "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp",
            HomeLink.Home(false, emptyList(), "example"),
        )
        val runtime = GlobalRoutingPolicy.apply(
            ConnectionCompatibility.apply(saved, CallingOptions(enabled = true)),
            directPackages = setOf("org.example.one", "org.example.two"),
        )
        val selected = JSONObject(runtime).getJSONArray("outbounds").getJSONObject(0).getJSONArray("outbounds").getString(0)
        return ConnectionChecks.inspect(ConnectionCheckInput(
            local = true, started = true, commandConnected = true, mode = ConnectionCheckMode.Global,
            ownership = VpnOwnership.Ours, vpnNetwork = 11, underlyingNetwork = 22,
            runtimeEpoch = 7, runtimeNetwork = 22, runtimeConfig = runtime,
            selections = mapOf("proxy" to selected), selectedProfileMatches = true,
            protection = VpnProtectionSnapshot(VpnProtectionReadability.Available, true, false),
        ))
    }

    private fun savePreview(name: String) {
        val output = File(System.getProperty("knine.previewDir"), name)
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
