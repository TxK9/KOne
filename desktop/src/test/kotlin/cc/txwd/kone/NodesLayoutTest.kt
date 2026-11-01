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

@OptIn(ExperimentalTestApi::class)
class NodesLayoutTest {
    @Test
    fun narrowRowsKeepNamesAndActionsReachable() = runComposeUiTest {
        val name = "A provider name with a long region and protocol description"
        var selected by mutableStateOf<String?>(null)
        val calls = mutableListOf<String>()
        setContent {
            KoneTheme(false) {
                Box(Modifier.size(600.dp, 540.dp)) {
                    NodesView(NodesState(
                        sections = listOf(NodeSection("own", SourceKind.Imported, "Imported",
                            listOf(Node("one", name, "JP", "Japan", 85, protocol = "hysteria2", loss = 0.1)))),
                        selected = selected,
                    ), NodesActions(
                        onUse = { selected = it }, onCopyLink = { calls += "copy:$it" },
                        onQr = { calls += "qr:$it" }, onEditNode = { calls += "edit:$it" },
                        onRemoveNode = { calls += "remove:$it" }, onSpeed = { calls += "speed:$it" },
                    ))
                }
            }
        }
        onNodeWithText(name).assertIsDisplayed().performClick().assertIsSelected()
        onNodeWithText("Copy").assertIsDisplayed().performClick()
        onNodeWithText("QR").assertIsDisplayed().performClick()
        onNodeWithText("Edit").assertIsDisplayed().performClick()
        onNodeWithText("Speed").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Remove $name").assertIsDisplayed().performClick()
        assertEquals(listOf("copy:one", "qr:one", "edit:one", "speed:one", "remove:one"), calls)
        assertEquals("one", selected)
    }

    @Test
    fun largeManagedListsFilterWithoutExposingSharingActions() = runComposeUiTest {
        val nodes = (0 until 1000).map { Node("n$it", "Managed node $it", "JP", "Japan", it + 1, protocol = "vless") }
        setContent {
            KoneTheme(true) {
                Box(Modifier.size(600.dp, 540.dp)) {
                    NodesView(NodesState(sections = listOf(NodeSection("managed", SourceKind.Managed, "Managed", nodes))), NodesActions())
                }
            }
        }
        assertTrue(onAllNodes(hasText("Managed node", substring = true)).fetchSemanticsNodes().size < 20)
        onNodeWithText("Copy").assertDoesNotExist()
        onNodeWithText("QR").assertDoesNotExist()
        onNodeWithTag("nodes-list").performScrollToIndex(1000)
        onNode(hasText("Managed node 999") and !hasSetTextAction()).assertIsDisplayed()
        onNode(hasSetTextAction()).performTextInput("Managed node 999")
        onNode(hasText("Managed node 999") and !hasSetTextAction()).assertIsDisplayed()
        onNodeWithText("Managed node 998").assertDoesNotExist()
        onNode(hasSetTextAction()).performTextClearance()
        onNodeWithTag("nodes-list").performScrollToIndex(0)
        onNodeWithText("Managed node 0").assertIsDisplayed()
    }

    @Test
    fun longSubscriptionTitlesLeaveUpdateAndRemoveVisible() = runComposeUiTest {
        val title = "A subscription title that is much longer than the window can display beside all of its metadata"
        var updates = 0
        var removes = 0
        setContent {
            KoneTheme(false) {
                Box(Modifier.size(600.dp, 540.dp)) {
                    NodesView(NodesState(sections = listOf(NodeSection("feed", SourceKind.Subscription, title, emptyList(),
                        error = "The provider cannot be reached. Try updating again later.", left = 12))),
                        NodesActions(onUpdate = { updates++ }, onRemoveSubscription = { removes++ }))
                }
            }
        }
        onNodeWithText("Update").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Remove subscription $title").assertIsDisplayed().performClick()
        assertEquals(1, updates)
        assertEquals(1, removes)
    }
}
