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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.CollapsedPanel
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState
import io.nekohasekai.sfa.compose.screen.dashboard.KnineHost
import io.nekohasekai.sfa.compose.theme.plainKnineColorScheme
import io.nekohasekai.sfa.constant.Status
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
    private fun Page(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            MaterialTheme(colorScheme = plainKnineColorScheme(dark)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        val output = File(System.getProperty("knine.previewDir"), name)
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
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
}
