package io.nekohasekai.sfa.privacy

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.screen.dashboard.*
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsUiState
import io.nekohasekai.sfa.compose.screen.log.*
import io.nekohasekai.sfa.compose.screen.profile.EditProfileUiState
import io.nekohasekai.sfa.compose.screen.profile.KnineNodeEditor
import io.nekohasekai.sfa.compose.screen.tools.UdpTestPanel
import io.nekohasekai.sfa.compose.screen.tools.OutboundPickerContent
import io.nekohasekai.sfa.compose.screen.tools.OutboundPickerRow
import io.nekohasekai.sfa.compose.topbar.LocalTopBarController
import io.nekohasekai.sfa.compose.topbar.TopBarController
import io.nekohasekai.sfa.compose.topbar.TopBarEntry
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.udp.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
class PrivacyUiTest {
    @get:Rule val compose = createComposeRule()
    private val secretHost = "private-node.example.invalid"
    private val secretIp = "203.0.113.42"
    private val secretTag = "vless://private-credential@$secretHost:443"

    @Before fun attachPreferencesWithoutStartingCore() {
        val application = io.nekohasekai.sfa.Application()
        val context: Application = RuntimeEnvironment.getApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(
            application,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context),
        )
    }

    @Test fun nodeEditorKeepsAppearanceAndUpdateControlsWithoutExposingConfiguration() {
        val profile = Profile(id = 41, name = "Current node")
        compose.setContent {
            MaterialTheme {
                KnineNodeEditor(
                    state = EditProfileUiState(
                        profile = profile, name = profile.name, originalName = profile.name,
                        profileType = TypedProfile.Type.Remote,
                        remoteUrl = "https://$secretHost/subscription?token=private-credential",
                        isLoading = false,
                    ),
                    onName = {}, onIcon = {}, onMoreIcons = {}, onAutoUpdate = {},
                    onAutoUpdateInterval = {}, onUpdateNow = {}, onDeleted = {}, onLeaveOut = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Current node").assertIsDisplayed()
        compose.onNodeWithText("Connection settings").assertDoesNotExist()
        compose.onNodeWithText("Update now").assertIsDisplayed()
        for (label in listOf("Profile content", "View profile", "Subscription URL", "Share", "QR code", secretHost, "private-credential")) {
            compose.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
    }

    @Test fun udpResultKeepsLatencyWithoutAddressOrInternalTag() {
        val target = UdpTarget(41, "Current node", secretTag, 1, 1, false)
        compose.setContent {
            MaterialTheme {
                UdpTestPanel(
                    "Current node",
                    UdpCheckState(target, UdpPhase.Passed, UdpMeasurement(true, UdpReply("$secretIp:3478", 123), 1)),
                    "Connect first", {}, {}, {},
                )
            }
        }
        compose.onNodeWithText("Available").assertIsDisplayed()
        compose.onNodeWithText("123").assertIsDisplayed()
        compose.onNodeWithText(secretIp, substring = true).assertDoesNotExist()
        compose.onNodeWithText(secretTag, substring = true).assertDoesNotExist()
    }

    @Test fun collapsedDashboardDoesNotRevealDetectedExitAddress() {
        compose.setContent {
            MaterialTheme {
                CollapsedPanel(
                    DashboardUiState(), Status.Started, ExitIpUi(ExitInfo(secretIp, "US")),
                    KnineHost(), {}, Modifier,
                )
            }
        }
        compose.onNodeWithText("No node selected").assertIsDisplayed()
        compose.onNodeWithText(secretIp, substring = true).assertDoesNotExist()
    }

    @Test fun groupsUsePublicAliasesAndStillSelectTheCorrectInternalRoute() {
        var selected: Pair<String, String>? = null
        val item = GroupItem(secretTag, "vless", "VLESS", 0, 123)
        val group = Group(secretHost, "selector", secretTag, true, "", true, listOf(item))
        compose.setContent {
            MaterialTheme {
                GroupsCardContent(
                    GroupsUiState(groups = listOf(group), expandedGroups = setOf(group.tag)),
                    onToggleExpanded = {}, onItemSelected = { g, i -> selected = g to i },
                    onUrlTest = {}, onItemUrlTest = {},
                )
            }
        }
        compose.onNodeWithText("Group 1").assertIsDisplayed()
        compose.onNodeWithText("Route 1").assertIsDisplayed().performClick()
        assertEquals(group.tag to item.tag, selected)
        compose.onNodeWithText(secretHost, substring = true).assertDoesNotExist()
        compose.onNodeWithText(secretTag, substring = true).assertDoesNotExist()
        compose.onNodeWithText("VLESS").assertDoesNotExist()
    }

    @Test fun logRouteNeverRendersOrExportsRawCoreMessages() {
        val viewer = PrivateLogFixture("dial $secretTag ($secretIp): private-credential")
        compose.setContent {
            MaterialTheme {
                val entries = remember { mutableStateOf<List<TopBarEntry>>(emptyList()) }
                val controller = remember { TopBarController(entries) }
                CompositionLocalProvider(LocalTopBarController provides controller) {
                    Column {
                        controller.current?.invoke()
                        LogScreen(serviceStatus = Status.Started, viewModel = viewer)
                    }
                }
            }
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        for (label in listOf(secretHost, secretIp, "private-credential", "Copy", "Export", "Share")) {
            compose.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
        assertEquals(0, viewer.exports)
    }

    @Test fun outboundPickerShowsOnlyPublicNumbersAndReturnsTheOriginalTag() {
        val first = GroupItem(secretTag, "vless", "VLESS", 0, 123)
        val second = GroupItem("trojan://other-credential@$secretHost:8443", "trojan", "Trojan", 0, 234)
        var selected by mutableStateOf(first.tag)
        compose.setContent {
            MaterialTheme {
                Column {
                    OutboundPickerRow(selected, {})
                    OutboundPickerContent(listOf(first, second), selected) { selected = it }
                }
            }
        }
        compose.onNodeWithText("Route selected").assertIsDisplayed()
        compose.onNodeWithText("Route 1").assertIsDisplayed()
        compose.onNodeWithText("Route 2").assertIsDisplayed()
        for (label in listOf(secretHost, secretTag, "private-credential", "other-credential", "VLESS", "Trojan")) {
            compose.onNodeWithText(label, substring = true).assertDoesNotExist()
        }
        compose.onNode(hasSetTextAction()).performTextInput("Route 2")
        compose.onNodeWithText("Route 1").assertDoesNotExist()
        compose.onNode(hasText("Route 2") and !hasSetTextAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(second.tag, selected) }
        compose.onNodeWithText(second.tag, substring = true).assertDoesNotExist()
    }

    private class PrivateLogFixture(message: String) : LogViewerViewModel {
        var exports = 0
        override val uiState = MutableStateFlow(LogUiState(logs = listOf(
            ProcessedLogEntry(1, LogEntryData(LogLevel.ERROR, message), androidx.compose.ui.text.AnnotatedString(message)),
        )))
        override val scrollToBottomTrigger = MutableStateFlow(0)
        override val isAtBottom = MutableStateFlow(true)
        override fun getAllLogsText(): String { exports++; return "private" }
        override fun getSelectedLogsText(): String { exports++; return "private" }
        override fun updateServiceStatus(status: Status) {}
        override fun togglePause() {}
        override fun toggleSearch() {}
        override fun toggleOptionsMenu() {}
        override fun updateSearchQuery(query: String) {}
        override fun setLogLevel(level: LogLevel) {}
        override fun setAutoScrollEnabled(enabled: Boolean) {}
        override fun scrollToBottom() {}
        override fun toggleSelectionMode() {}
        override fun toggleLogSelection(index: Int) {}
        override fun clearSelection() {}
        override fun requestClearLogs() {}
    }
}
