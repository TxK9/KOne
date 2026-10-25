package io.nekohasekai.sfa.connection

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.nekohasekai.sfa.compose.screen.tools.CallingOptionsEditor
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
class CallingFailurePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun FailedReloadKeepsRecoveryMessageWhileEditingIsDisabled() {
        val result = CallingApplyResult.Failed(CallingApplyFailure.ReloadFailed, CallingSettingsRecovery.RestoreFailed)
        compose.setContent {
            MaterialTheme {
                CallingOptionsEditor(CallingOptions(true), false, callingUpdateMessage(result), enabled = false) {}
            }
        }
        compose.onNodeWithText("Settings could not be fully restored. Check them and reconnect.").assertIsDisplayed()
        compose.onNodeWithText("Connect to a node to edit").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithText("Advanced options").assertIsNotEnabled()
        compose.onNodeWithText("Applied to the active connection").assertDoesNotExist()
    }
}
