package io.nekohasekai.sfa.compose.screen.account

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.Assert.assertNotEquals
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
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

    private val now = 1_793_000_000_000L // 2026-10-26T07:33:20Z, fixed so the preview does not drift
    private val utc = ZoneId.of("UTC")
    private val clock = AccountClock(now, utc)

    private fun snapshot(
        status: ManagedStatus,
        detail: String? = null,
        diagnostics: Boolean = false,
        expiresAt: String = "2026-10-28",
    ) = ManagedSnapshot(
        endpoint = "https://console.example.com",
        deviceName = "KOne · Example Phone",
        bound = true,
        profileId = 7L,
        configName = "Tokyo 1",
        groupName = "Standard",
        policy = ManagedPolicy(quotaBytes = 100_000_000_000L, deviceLimit = 3, expiresAt = expiresAt, period = "monthly"),
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

    /**
     * Every text laid out on the page shows all of itself, read from its actual text layout: no
     * line limit cut it, no line ends in an ellipsis, no line is wider than the text's own box, the
     * box is not clipped in height, and the box lies inside the screen.
     *
     * (`hasVisualOverflow` is not used: here it is true for any short text, as the paragraph is
     * laid out at the constraint's width while the box takes the text's own.)
     */
    private fun assertNoTextIsCut() {
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        for (node in nodes) {
            val text = node.config[SemanticsProperties.Text].joinToString()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            assertTrue("no text layout for \"$text\"", layouts.isNotEmpty())
            for (layout in layouts) {
                assertFalse("\"$text\" is cut by its line limit", layout.multiParagraph.didExceedMaxLines)
                assertFalse("\"$text\" is clipped in height", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("\"$text\" is ellipsized", layout.isLineEllipsized(line))
                    assertTrue("\"$text\" line $line is wider than its box", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 0.5f)
                }
            }
            val bounds = node.boundsInRoot
            assertTrue("\"$text\" runs past the screen's edge", bounds.left >= -0.5f && bounds.right <= rootWidth + 0.5f)
        }
    }

    @Test fun syncedAccountShowsItsTermsAndActions() {
        var syncs = 0
        var unlinks = 0
        compose.setContent {
            Page(dark = false) {
                ManagedAccountContent(snapshot(ManagedStatus.Synced), clock, syncing = false, unlinking = false, unlinkFailed = false,
                    onSync = { syncs++ }, onFeedback = {}, onUnlink = { unlinks++ })
            }
        }
        compose.onNodeWithText("Synced").assertIsDisplayed()
        compose.onNodeWithText("Standard").assertIsDisplayed()
        compose.onNodeWithText("Tokyo 1").assertIsDisplayed()
        compose.onNodeWithText("Expires").assertIsDisplayed()
        assertNoTextIsCut()
        capture("account-synced-light.png")
        compose.onNodeWithText("Sync now").assertHasClickAction().performClick()
        compose.onNodeWithText("Share diagnostics", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Unlink this device").performClick()
        assertEquals(1, syncs)
        assertEquals(1, unlinks)
    }

    @Test fun offlineAccountKeepsItsReasonOneTapAway() {
        val context: Application = org.robolectric.RuntimeEnvironment.getApplication()
        val offline = context.getString(io.nekohasekai.sfa.R.string.kone_status_offline)
        val hint = context.getString(io.nekohasekai.sfa.R.string.kone_account_offline_hint)
        val syncing = context.getString(io.nekohasekai.sfa.R.string.kone_account_syncing)
        compose.setContent {
            Page(dark = true) {
                ManagedAccountContent(snapshot(ManagedStatus.Offline, diagnostics = true), clock, syncing = true, unlinking = false, unlinkFailed = false,
                    onSync = {}, onFeedback = {}, onUnlink = {})
            }
        }
        compose.onNodeWithText(hint, substring = true).assertDoesNotExist()
        compose.onNodeWithText(offline).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(hint, substring = true).assertIsDisplayed()
        compose.onNodeWithText(syncing).assertIsDisplayed()
        capture("account-offline-dark.png")
    }

    @Test fun accountRowsWrapAtDoubleFontSize() {
        compose.setContent {
            Page(dark = false, fontScale = 2f) {
                ManagedAccountContent(snapshot(ManagedStatus.Refused, detail = "Renewal pending"), clock, syncing = false, unlinking = false, unlinkFailed = false,
                    onSync = {}, onFeedback = {}, onUnlink = {})
            }
        }
        compose.onNodeWithText("Service paused").assertIsDisplayed()
        assertNoTextIsCut()
        // The whole value, under its title when the two no longer fit side by side.
        val dataTitle = compose.onNodeWithText("Data allowance", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dataValue = compose.onNodeWithText("100 GB · monthly", useUnmergedTree = true).assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue("value stacks under its title", dataValue.top >= dataTitle.bottom)
        val deviceTitle = compose.onNodeWithText("This device", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val deviceValue = compose.onNodeWithText("KOne · Example Phone", useUnmergedTree = true).assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue("value stacks under its title", deviceValue.top >= deviceTitle.bottom)
        capture("account-paused-font200.png")
    }

    @Test fun anEndWithinTheHourTurnsToEndedOnTheMinuteTick() {
        // 07:33:20 and 07:41:00 share an hour; the end at 07:40 must still show on the next tick.
        var tick by mutableStateOf(clock)
        compose.setContent {
            Page(dark = false) {
                ManagedAccountContent(snapshot(ManagedStatus.Synced, expiresAt = "2026-10-26T07:40:00Z"), tick, syncing = false, unlinking = false,
                    unlinkFailed = false, onSync = {}, onFeedback = {}, onUnlink = {})
            }
        }
        compose.onNodeWithText("Expires").assertIsDisplayed()
        compose.onNodeWithText("Ended").assertDoesNotExist()
        compose.runOnIdle { tick = AccountClock(now + 7 * 60_000L + 40_000L, utc) }
        compose.onNodeWithText("Ended").assertIsDisplayed()
        compose.onNodeWithText("Expires").assertDoesNotExist()
    }

    @Test fun theEndDateFollowsTheZoneReadWithTheClock() {
        var tick by mutableStateOf(clock)
        compose.setContent {
            Page(dark = false) {
                ManagedAccountContent(snapshot(ManagedStatus.Synced, expiresAt = "2026-12-01T20:00:00Z"), tick, syncing = false, unlinking = false,
                    unlinkFailed = false, onSync = {}, onFeedback = {}, onUnlink = {})
            }
        }
        compose.onNodeWithText(expiryDateText(LocalDate.of(2026, 12, 1), Locale.US), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { tick = AccountClock(now + 60_000L, ZoneId.of("Asia/Tokyo")) }
        compose.onNodeWithText(expiryDateText(LocalDate.of(2026, 12, 2), Locale.US), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun theEndDateFollowsTheAppLocale() {
        var locale by mutableStateOf(Locale.US)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                Page(dark = false) {
                    ManagedAccountContent(snapshot(ManagedStatus.Synced), clock, syncing = false, unlinking = false,
                        unlinkFailed = false, onSync = {}, onFeedback = {}, onUnlink = {})
                }
            }
        }
        val date = LocalDate.of(2026, 10, 28)
        assertNotEquals(expiryDateText(date, Locale.US), expiryDateText(date, Locale.GERMANY))
        compose.onNodeWithText(expiryDateText(date, Locale.US), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { locale = Locale.GERMANY }
        compose.onNodeWithText(expiryDateText(date, Locale.GERMANY), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(expiryDateText(date, Locale.US), useUnmergedTree = true).assertDoesNotExist()
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
