package io.nekohasekai.sfa.compose.component

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.CollapsedPanel
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState
import io.nekohasekai.sfa.compose.screen.dashboard.KnineHost
import io.nekohasekai.sfa.compose.theme.plainKnineColorScheme
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.compose.screen.feedback.FeedbackForm
import io.nekohasekai.sfa.compose.screen.feedback.FeedbackProblem
import io.nekohasekai.sfa.compose.screen.tools.TestNote
import org.json.JSONObject
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.ResolvedTextDirection
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual presentation components with synthetic status; no application, account or network. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class HubPolishVisualTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Page(dark: Boolean = false, fontScale: Float = 1f, rtl: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            MaterialTheme(colorScheme = plainKnineColorScheme(dark)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { content() }
                }
            }
        }
    }

    private fun capture(name: String): Bitmap {
        val output = File(System.getProperty("knine.previewDir"), name)
        output.parentFile!!.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun hub(dark: Boolean, fontScale: Float, name: String) {
        var opened = 0
        compose.setContent {
            Page(dark, fontScale) {
                KnineRowGroup {
                    KnineNavRow(stringResource(R.string.kone_account), Icons.Outlined.AccountCircle,
                        onClick = { opened++ }, supporting = "Synced · 4 minutes ago")
                    KnineNavRow(stringResource(R.string.title_app_settings), Icons.Outlined.PhoneAndroid,
                        onClick = {}, supporting = "Update available", badge = MaterialTheme.colorScheme.primary,
                        badgeDescription = "Update available")
                    KnineNavRow(stringResource(R.string.privilege_settings), Icons.Outlined.AdminPanelSettings, onClick = {})
                }
            }
        }
        capture(name)
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        for (node in textNodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                assertFalse("Navigation text exceeds line limit: ${node.config[SemanticsProperties.Text]}", layout.multiParagraph.didExceedMaxLines)
                assertFalse(layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
            }
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.kone_account)).performClick()
        assertEquals(1, opened)
    }

    @Test fun hubLightHasReadableHierarchy() = hub(false, 1f, "hub-light.png")
    @Test fun hubDarkHasReadableHierarchy() = hub(true, 1f, "hub-dark.png")
    @Test fun hubLargeTextKeepsCompleteNavigationLabels() = hub(false, 2f, "hub-font200.png")

    @Test fun loadingSettlesIntoTheEmptySelector() {
        var loading by mutableStateOf(true)
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            Page {
                CollapsedPanel(DashboardUiState(isLoading = loading), Status.Stopped, KnineHost(), {}, Modifier)
            }
        }
        capture("dashboard-loading.png")
        compose.onNodeWithText(context.getString(R.string.loading)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.kone_dashboard_no_node)).assertDoesNotExist()
        compose.runOnIdle { loading = false }
        compose.onNodeWithText(context.getString(R.string.loading)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.kone_dashboard_no_node)).assertIsDisplayed()
    }

    @Test fun emptySelectorKeepsItsActionAndTouchTarget() {
        var opened = 0
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            Page { CollapsedPanel(DashboardUiState(), Status.Stopped, KnineHost(), { opened++ }, Modifier) }
        }
        capture("dashboard-empty.png")
        val selector = compose.onNodeWithText(context.getString(R.string.kone_dashboard_no_node))
        val density = context.resources.displayMetrics.density
        assertTrue("Selector has a 48dp minimum target", selector.fetchSemanticsNode().boundsInRoot.height >= 48f * density)
        selector.performClick()
        assertEquals(1, opened)
    }

    @Test fun disabledNavigationRejectsBothPointerAndSemanticClicks() {
        var enabled by mutableStateOf(false)
        var opened = 0
        compose.setContent {
            Page {
                KnineRowGroup {
                    // Match the existing privilege-row caller's disabled presentation.
                    KnineNavRow("Manage apps", Icons.Outlined.AdminPanelSettings, { opened++ },
                        enabled = enabled, modifier = Modifier.testTag("navigation").alpha(if (enabled) 1f else 0.38f))
                }
            }
        }
        val row = compose.onNodeWithTag("navigation")
        row.assertIsNotEnabled().performTouchInput { click() }
        row.performSemanticsAction(SemanticsActions.OnClick) { it() }
        val blockedClicks = opened
        capture("navigation-disabled.png")
        compose.runOnIdle { enabled = true }
        row.assertIsEnabled().performClick()
        capture("navigation-enabled.png")
        assertEquals("Disabled row ignores pointer and direct semantic actions", 0, blockedClicks)
        assertEquals(blockedClicks + 1, opened)
    }

    @Test fun badgeAndTextStaySeparateAtLargeFontInBothDirectionsAndThemes() {
        var rtl by mutableStateOf(false)
        var dark by mutableStateOf(false)
        val marker = 0xffc50065.toInt()
        compose.setContent {
            Page(dark = dark, fontScale = 2f, rtl = rtl) {
                KnineRowGroup {
                    KnineNavRow("Privileged Enhancement", Icons.Outlined.AdminPanelSettings, {},
                        supporting = "Update available", badge = androidx.compose.ui.graphics.Color(marker),
                        badgeDescription = "Update available", modifier = Modifier.testTag("badge-row"))
                    KnineNavRow("Remote Control", Icons.Outlined.PhoneAndroid, {}, modifier = Modifier.testTag("arrow-row"))
                }
            }
        }
        for (direction in listOf(false, true)) for (theme in listOf(false, true)) {
            compose.runOnIdle { rtl = direction; dark = theme }
            compose.mainClock.advanceTimeBy(300)
            val row = compose.onNodeWithTag("badge-row")
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Update available"))
            compose.onNodeWithTag("arrow-row").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            val frame = capture("navigation-${if (theme) "dark" else "light"}-${if (direction) "rtl" else "ltr"}-font200.png")
            val columns = mutableListOf<Int>()
            for (y in 0 until frame.height) for (x in 0 until frame.width) if (frame.getPixel(x, y) == marker) columns += x
            assertTrue("Badge is painted", columns.isNotEmpty())
            for (label in listOf("Privileged Enhancement", "Update available")) {
                val node = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                assertTrue(layouts.isNotEmpty())
                for (layout in layouts) {
                    assertFalse(layout.multiParagraph.didExceedMaxLines)
                    assertFalse(layout.didOverflowHeight)
                    for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                }
                if (direction) assertTrue("RTL badge stays before text bounds", columns.max() < node.boundsInRoot.left)
                else assertTrue("LTR badge stays after text bounds", columns.min() > node.boundsInRoot.right)
            }
        }
    }

    @Test fun rtlDashboardLoadingSettlesAndKeepsItsNodeActionAtLargeFont() {
        var loading by mutableStateOf(true)
        var opened = 0
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            Page(fontScale = 2f, rtl = true) {
                CollapsedPanel(DashboardUiState(isLoading = loading), Status.Stopped, KnineHost(), { opened++ }, Modifier)
            }
        }
        compose.onNodeWithText(context.getString(R.string.loading)).assertIsDisplayed()
        capture("dashboard-rtl-font200-loading.png")
        compose.runOnIdle { loading = false }
        compose.onNodeWithText(context.getString(R.string.loading)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.kone_dashboard_no_node)).assertIsDisplayed().performClick()
        assertEquals(1, opened)
        capture("dashboard-rtl-font200-empty.png")
    }

    @Test fun rtlFeedbackPreservesDraftAndRetryAcrossBusyAndErrorStates() {
        var busy by mutableStateOf(false)
        var problem by mutableStateOf<FeedbackProblem?>(null)
        var description by mutableStateOf("No response after changing routes")
        var submissions = 0
        val context = RuntimeEnvironment.getApplication()
        val reason = "Connection interrupted. Your draft is saved. Try again."
        compose.setContent {
            Page(fontScale = 2f, rtl = true) {
                FeedbackForm(description, emptyList(), busy, problem, { description = it }, {}, {}, {
                    if (!busy) { submissions++; busy = true; problem = null }
                })
            }
        }
        val send = context.getString(R.string.kone_feedback_send)
        compose.onNodeWithText(send).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.kone_feedback_sending)).assertIsNotEnabled()
        val editor = compose.onNodeWithText(description).fetchSemanticsNode()
        editor.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("Unexpected edit"))
        assertEquals("No response after changing routes", description)
        capture("feedback-rtl-font200-busy.png")
        compose.runOnIdle { busy = false; problem = FeedbackProblem("Not sent", reason) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Not sent").performScrollTo().performClick()
        compose.onNodeWithText(reason).performScrollTo().assertIsDisplayed()
        capture("feedback-rtl-font200-error.png")
        compose.onNodeWithText(send).performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, submissions)
        assertEquals("No response after changing routes", description)
    }

    @Test fun rtlFeedbackImageActionsKeepTheirIndicesAtLargeFont() {
        val removed = mutableListOf<Int>()
        val images = (0 until 3).map { index ->
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(40 * index, 100, 160)) }
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            JSONObject().put("mime", "image/jpeg").put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
        compose.setContent {
            Page(fontScale = 2f, rtl = true) { FeedbackForm("", images, false, null, {}, {}, { removed += it }, {}) }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.kone_feedback_add_image)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.kone_feedback_remove_image, 2)).performScrollTo().performClick()
        assertEquals(listOf(1), removed)
        capture("feedback-rtl-font200-images.png")
    }

    @Test fun rtlReasonsFollowTheirContentDirectionWithoutMovingLatinPunctuation() {
        val context = RuntimeEnvironment.getApplication()
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("fa")) }
        val localized = context.createConfigurationContext(configuration).getString(R.string.loading)
        val reason = "Connection interrupted. Your draft is saved. Try again."
        compose.setContent { Page(fontScale = 2f, rtl = true) { TestNote(reason); TestNote(localized) } }
        fun layout(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val latin = layout(reason)
        assertEquals("Latin reason keeps its own paragraph direction", ResolvedTextDirection.Ltr, latin.getParagraphDirection(0))
        assertTrue("Terminal punctuation follows the Latin word", latin.getBoundingBox(reason.lastIndex).center.x > latin.getBoundingBox(reason.lastIndex - 1).center.x)
        assertEquals("Existing RTL localization keeps its own direction", ResolvedTextDirection.Rtl, layout(localized).getParagraphDirection(0))
    }
}
