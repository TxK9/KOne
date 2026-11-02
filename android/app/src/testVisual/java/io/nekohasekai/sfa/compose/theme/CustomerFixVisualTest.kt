package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.screen.dashboard.NodeDeck
import io.nekohasekai.sfa.compose.screen.dashboard.NodeGroup
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
class CustomerFixVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun foldedUnselectedNodeDoesNotClipAtTripleFontSize() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 3f)) {
                MaterialTheme {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                        io.nekohasekai.sfa.compose.screen.dashboard.CollapsedPanel(
                            uiState = io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState(),
                            serviceStatus = io.nekohasekai.sfa.constant.Status.Stopped,
                            host = io.nekohasekai.sfa.compose.screen.dashboard.KnineHost(),
                            onSwitch = {}, modifier = Modifier.weight(1f),
                        )
                        Text("Connection", Modifier.width(72.dp))
                        }
                        Text("Content below")
                    }
                }
            }
        }
        compose.waitForIdle()
        val label = compose.onNodeWithText("No node selected").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val below = compose.onNodeWithText("Content below").fetchSemanticsNode().boundsInRoot
        assertTrue("fixed row height clips font", label.height >= 100f)
        assertTrue("next row overlaps text", below.top >= label.bottom)
        val output = File(System.getProperty("knine.previewDir"), "unselected-node-font300.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun emptyNodeDeckKeepsLargeTextAndImportButtonApart() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Column(Modifier.padding(16.dp)) {
                        NodeDeck(
                            groups = listOf(NodeGroup("local", "Local nodes", null, true, emptyList())),
                            pagerState = rememberPagerState { 1 }, selectedId = -1,
                            clear = true, editing = null, onEditingChange = {}, floating = false,
                            onSelect = {}, onEdit = {}, onOpenDial = {}, onImport = {},
                        )
                        Text("No node selected")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val tab = compose.onNodeWithText("Local nodes", true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("Import nodes", true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val below = compose.onNodeWithText("No node selected", true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("category glyphs clipped", tab.height >= 72f)
        assertTrue("import overlaps category", button.top >= tab.bottom)
        assertTrue("selection overlaps import", below.top >= button.bottom)
        val output = File(System.getProperty("knine.previewDir"), "empty-nodes-font200.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
