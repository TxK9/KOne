package io.nekohasekai.sfa.compose.screen.account

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.component.KnineNavRow
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.theme.plainKnineColorScheme
import io.nekohasekai.sfa.managed.ManagedPolicy
import io.nekohasekai.sfa.managed.ManagedSnapshot
import io.nekohasekai.sfa.managed.ManagedStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Synthetic accounts only: documentation names, no real service, key or device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class AccountVisualTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_793_000_000_000L // 2026-10-26, fixed so the preview does not drift

    private fun snapshot(status: ManagedStatus, detail: String? = null, diagnostics: Boolean = false) = ManagedSnapshot(
        endpoint = "https://console.example.com",
        deviceName = "KOne · Example Phone",
        bound = true,
        profileId = 7L,
        configName = "Tokyo 1",
        groupName = "Standard",
        policy = ManagedPolicy(quotaBytes = 100_000_000_000L, deviceLimit = 3, expiresAt = "2026-10-28", period = "monthly"),
        syncedAt = now - 4 * 60_000L,
        status = status,
        detail = detail,
        diagnostics = diagnostics,
    )

    @Composable
    private fun Page(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            MaterialTheme(colorScheme = plainKnineColorScheme(dark)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        val output = File(System.getProperty("knine.previewDir"), name)
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun syncedAccountShowsItsTermsAndActions() {
        var syncs = 0
        var unlinks = 0
        compose.setContent {
            Page(dark = false) {
                ManagedAccountContent(snapshot(ManagedStatus.Synced), now, syncing = false, unlinking = false, unlinkFailed = false,
                    onSync = { syncs++ }, onFeedback = {}, onUnlink = { unlinks++ })
            }
        }
        compose.onNodeWithText("Synced").assertIsDisplayed()
        compose.onNodeWithText("Standard").assertIsDisplayed()
        compose.onNodeWithText("Tokyo 1").assertIsDisplayed()
        compose.onNodeWithText("Expires").assertIsDisplayed()
        capture("account-synced-light.png")
        compose.onNodeWithText("Sync now").assertHasClickAction().performClick()
        compose.onNodeWithText("Share diagnostics", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Unlink this device").performClick()
        assertEquals(1, syncs)
        assertEquals(1, unlinks)
    }

    @Test fun offlineAccountKeepsItsReasonOneTapAway() {
        compose.setContent {
            Page(dark = true) {
                ManagedAccountContent(snapshot(ManagedStatus.Offline, diagnostics = true), now, syncing = true, unlinking = false, unlinkFailed = false,
                    onSync = {}, onFeedback = {}, onUnlink = {})
            }
        }
        compose.onNodeWithText("KOne keeps using the last configuration it received.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cannot reach the service").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("KOne keeps using the last configuration it received.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Syncing").assertIsDisplayed()
        capture("account-offline-dark.png")
    }

    @Test fun accountRowsWrapAtDoubleFontSize() {
        compose.setContent {
            Page(dark = false, fontScale = 2f) {
                ManagedAccountContent(snapshot(ManagedStatus.Refused, detail = "Renewal pending"), now, syncing = false, unlinking = false, unlinkFailed = false,
                    onSync = {}, onFeedback = {}, onUnlink = {})
            }
        }
        compose.onNodeWithText("Service paused").assertIsDisplayed()
        capture("account-paused-font200.png")
    }

    @Test fun hubRowsShareOneCardAndTheirBadges() {
        var opened = 0
        compose.setContent {
            Page(dark = false) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    KnineRowGroup {
                        KnineNavRow("Account", Icons.Outlined.AccountCircle, onClick = { opened++ }, supporting = "Synced · 4 minutes ago")
                        KnineNavRow("App", Icons.Outlined.PhoneAndroid, onClick = {}, supporting = "Update available",
                            badge = MaterialTheme.colorScheme.primary, badgeDescription = "Update available")
                    }
                    KnineSectionLabel("Connection", Modifier.padding(top = 8.dp))
                    KnineRowGroup {
                        KnineNavRow("Service", Icons.Outlined.Tune, onClick = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Account").performClick()
        assertEquals(1, opened)
        capture("settings-hub-light.png")
    }
}
