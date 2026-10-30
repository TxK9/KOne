package cc.txwd.kone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.LogView
import cc.txwd.kone.ui.severity
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LogFilteringTest {
    @Test
    fun filteringTracksRolloverAndPauseKeepsASnapshot() = runComposeUiTest {
        val lines = mutableStateListOf("INFO first", "WARN second", "ERROR third")
        var copied = emptyList<String>()
        setContent {
            KoneTheme(false) {
                Box(Modifier.size(600.dp, 500.dp)) { LogView(lines, onCopy = { copied = it }, onClear = { lines.clear() }) }
            }
        }
        onNodeWithText("Warnings").performClick()
        onNodeWithText("Copy").performClick()
        assertEquals(listOf("WARN second", "ERROR third"), copied)
        onNodeWithText("Pause").performClick()
        runOnIdle { lines.removeAt(0); lines.add("ERROR fourth") }
        onNodeWithText("Copy").performClick()
        assertEquals(listOf("WARN second", "ERROR third"), copied)
        onNodeWithText("Resume").performClick()
        onNodeWithText("Copy").performClick()
        assertEquals(listOf("WARN second", "ERROR third", "ERROR fourth"), copied)
        onNode(hasSetTextAction()).performTextInput(" fourth ")
        onNodeWithText("Copy").performClick()
        assertEquals(listOf("ERROR fourth"), copied)
        onNodeWithText("Clear").performClick()
        onNodeWithText("Nothing logged yet").assertIsDisplayed()
    }

    @Test
    fun levelsKeepWordBoundariesAndPriority() {
        assertEquals(listOf(0, 1, 2, 2, 2, 0),
            listOf("INFO", "WARN", "ERROR", "WARN FATAL", "PANIC", "WARNINGS NOERROR").map(::severity))
    }
}
