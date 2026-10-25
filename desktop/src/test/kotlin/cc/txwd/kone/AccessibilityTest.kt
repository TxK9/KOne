package cc.txwd.kone

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import cc.txwd.kone.ui.DashboardActions
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.DashboardView
import cc.txwd.kone.ui.Frame
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.KeyView
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.Page
import cc.txwd.kone.ui.RulesActions
import cc.txwd.kone.ui.RulesState
import cc.txwd.kone.ui.RulesView
import cc.txwd.kone.ui.SettingsActions
import cc.txwd.kone.ui.SettingsState
import cc.txwd.kone.ui.SettingsView
import cc.txwd.kone.ui.TitleBar
import cc.txwd.kone.ui.WithRail
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Backdrop
import cc.txwd.kone.ui.Connection
import kotlin.test.Test

/** What a screen reader is told: every control has a name, a role, and its state. */
@OptIn(ExperimentalTestApi::class)
class AccessibilityTest {
    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private fun toggled(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(on))
    private fun state(words: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, words)

    private val dashboard = DashboardState(
        categories = listOf("United States", "Japan"),
        nodes = listOf(Node("us1", "United States", "US", "United States", 245), Node("i1", "Friend's node", null, "United States", 220, imported = true)),
        tab = "United States", selected = "i1", connection = Connection.Online, exit = "203.0.113.24", canImport = true,
    )

    @Test
    fun theDashboardSaysWhatEachThingIs() = runComposeUiTest {
        setContent {
            KoneTheme(false) {
                Frame({ TitleBar() }) { WithRail(Page.Dashboard, {}) { DashboardView(dashboard, DashboardActions(), false, {}, moving = false) } }
            }
        }
        // The window's own buttons, named though they show only a glyph.
        onNodeWithContentDescription("Minimize").assert(role(Role.Button))
        onNodeWithContentDescription("Close").assert(role(Role.Button))
        // The rail's pages are tabs, the one shown selected.
        onNodeWithText("Dashboard").assert(role(Role.Tab)).assertIsSelected()
        onNodeWithText("Nodes").assertIsNotSelected()
        // A node to choose is a choice, the chosen one selected; its × says what it removes.
        onNodeWithText("Friend's node").assert(role(Role.RadioButton)).assertIsSelected()
        onNodeWithContentDescription("Remove Friend's node").assert(role(Role.Button))
        // The power button says what it does and the connection's state.
        onNodeWithText("Disconnect").assert(state("Connected"))
        onNodeWithContentDescription("Import nodes").assert(role(Role.Button))
    }

    @Test
    fun switchesSayTheirNameAndWhetherTheyAreOn() = runComposeUiTest {
        setContent {
            KoneTheme(false) {
                SettingsView(SettingsState(Appearance.System, Backdrop.City, autostart = false, hotkey = true, version = "1.0.0", update = cc.txwd.kone.ui.UpdateState.Latest), SettingsActions())
            }
        }
        onNodeWithContentDescription("Start with Windows").assert(role(Role.Switch)).assert(toggled(false)).assert(state("Off"))
        onNodeWithContentDescription("Keyboard shortcut").assert(role(Role.Switch)).assert(toggled(true)).assert(state("On"))
        onNodeWithText("System default").assert(role(Role.Tab)).assertIsSelected()
    }

    @Test
    fun arrowsAndCrossesAreNamed() = runComposeUiTest {
        val rules = listOf(cc.txwd.kone.core.UserRule("suffix", "example.net", "proxy"), cc.txwd.kone.core.UserRule("port", "6881", "direct"))
        setContent { KoneTheme(false) { RulesView(RulesState(rules = rules, direct = listOf("example.cn")), RulesActions()) } }
        onNodeWithContentDescription("Remove example.cn").assert(role(Role.Button))
        onAllNodesWithContentDescription("Move down")[0].assert(role(Role.Button))
    }

    @Test
    fun theInvitationFieldAndPasteAreNamed() = runComposeUiTest {
        setContent { KoneTheme(false) { KeyView(KeyState(), {}, {}, {}, moving = false) } }
        onNodeWithContentDescription("Invitation code").assertExists()
        onNodeWithContentDescription("Paste the invitation code").assert(role(Role.Button))
    }
}
