package cc.txwd.kone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import cc.txwd.kone.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Large subscriptions must not displace the power control or compose every node. */
@OptIn(ExperimentalTestApi::class)
class DashboardDeckTest {
    @Test
    fun reopeningTheDashboardRevealsTheSelectedNode() = runComposeUiTest {
        var showing by mutableStateOf(true)
        val state = DashboardState(listOf("Japan"),
            (0 until 1000).map { Node("jp$it", "Tokyo $it", "JP", "Japan", 80) }, "Japan", "jp999")
        setContent {
            KoneTheme(false) {
                Box(Modifier.size(600.dp, 540.dp)) {
                    if (showing) DashboardView(state, DashboardActions(), false, {}, moving = false)
                }
            }
        }
        onNodeWithText("Tokyo 999").assertIsDisplayed().assertIsSelected()
        runOnIdle { showing = false }
        onNodeWithText("Tokyo 999").assertDoesNotExist()
        runOnIdle { showing = true }
        onNodeWithText("Tokyo 999").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun largeListsScrollSelectAndResetWhenChangingCountry() = runComposeUiTest {
        var state by mutableStateOf(DashboardState(
            categories = listOf("Japan", "Singapore"),
            nodes = (0 until 1000).map { Node("jp$it", "Tokyo $it", "JP", "Japan", 80) } +
                Node("sg", "Singapore node", "SG", "Singapore", 90),
            tab = "Japan", selected = "jp0",
        ))
        var powers = 0
        setContent {
            KoneTheme(false) {
                Box(Modifier.size(600.dp, 540.dp)) {
                    DashboardView(state, DashboardActions(
                        onTab = { state = state.copy(tab = it) },
                        onNode = { state = state.copy(selected = it) },
                        onPower = { powers++ },
                    ), false, {}, moving = false)
                }
            }
        }
        onNodeWithText("Connect").assertIsDisplayed().performClick()
        assertEquals(1, powers)
        assertTrue(onAllNodes(hasText("Tokyo", substring = true)).fetchSemanticsNodes().size < 20)
        onNodeWithTag("dashboard-nodes").performScrollToIndex(999)
        onNodeWithText("Tokyo 999").assertIsDisplayed().performClick().assertIsSelected()
        onNodeWithText("Connect").assertIsDisplayed()
        onNodeWithText("Singapore").performClick()
        onNodeWithText("Singapore node").assertIsDisplayed().performClick().assertIsSelected()
        onNodeWithText("Japan").performClick()
        onNodeWithText("Tokyo 0").assertIsDisplayed()
        onNodeWithTag("dashboard-nodes").performScrollToIndex(999)
        onNodeWithText("Tokyo 999").performClick().assertIsSelected()
    }

    @Test
    fun connectionActionsStayVisibleWithLongOnlineDetails() = runComposeUiTest {
        setContent {
            KoneTheme(true) {
                Box(Modifier.size(600.dp, 540.dp)) {
                    DashboardView(DashboardState(
                        listOf("Japan"), listOf(Node("jp", "A very long provider node name that must not push actions away", "JP", "Japan", 81)),
                        "Japan", "jp", Connection.Online, exit = "2001:db8:1234:5678::1234", proxied = true,
                        expiry = "3 days remaining", standIn = "A replacement node", seconds = 120,
                    ), DashboardActions(), false, {}, moving = false)
                }
            }
        }
        onNodeWithText("Disconnect").assertIsDisplayed()
        onNodeWithText("Pause 15 min").assertIsDisplayed()
    }
}
