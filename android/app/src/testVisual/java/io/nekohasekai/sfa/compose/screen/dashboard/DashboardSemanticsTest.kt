package io.nekohasekai.sfa.compose.screen.dashboard

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.nekohasekai.sfa.constant.Status
import org.junit.Assert.assertEquals
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
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class DashboardSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFoldedPanelIsAButtonThatSaysItChoosesANode() {
        var switched = 0
        compose.setContent {
            MaterialTheme {
                CollapsedPanel(
                    uiState = DashboardUiState(),
                    serviceStatus = Status.Stopped,
                    host = KnineHost(),
                    onSwitch = { switched++ },
                    modifier = Modifier,
                )
            }
        }
        compose.onNodeWithText("No node selected")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("labels its tap") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Choose node" })
            .performClick()
        assertEquals(1, switched)
    }
}
