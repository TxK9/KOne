package cc.txwd.kone

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Backdrop
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.Licenses
import cc.txwd.kone.ui.SettingsActions
import cc.txwd.kone.ui.SettingsState
import cc.txwd.kone.ui.SettingsView
import cc.txwd.kone.ui.UpdateState
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The third-party notices: each text is carried, the overview names them all, and Settings shows them. */
@OptIn(ExperimentalTestApi::class)
class LicensesTest {
    @Test
    fun everyTextIsCarriedAndTheOverviewNamesIt() {
        val overview = Licenses.text("NOTICES.txt")
        for ((name, file) in Licenses.texts) {
            assertTrue(Licenses.text(file).isNotBlank(), "$name ($file) is not among the App's resources")
            if (file != "NOTICES.txt") assertTrue(file in overview, "The overview does not name $file")
        }
        // Each file the overview names is one Settings can show.
        val named = Regex("""[\w.-]+\.txt""").findAll(overview).map { it.value }.toSet()
        assertEquals(emptySet(), named - Licenses.texts.map { it.second }.toSet())
    }

    @Test
    fun theOverviewKeepsTheCoresNoticeWordForWord() {
        // GPL-3.0 asks that the notice stay intact; the sing-box source in core/ carries it.
        val notice = File("../core/LICENSE").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val overview = Licenses.text("NOTICES.txt").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = overview.indexOf(notice.first())
        assertTrue(start >= 0, "The overview does not carry the core's copyright line")
        assertEquals(notice, overview.subList(start, (start + notice.size).coerceAtMost(overview.size)))
    }

    @Test
    fun openedNoticesBringThemselvesIntoView() = runComposeUiTest {
        // Below the last line of a page taller than the window: the page scrolls to show them.
        setContent {
            KoneTheme(false) {
                SettingsView(SettingsState(Appearance.System, Backdrop.Ink, autostart = false, hotkey = true, version = "1.0.0", update = UpdateState.Latest), SettingsActions(), noticesOpen = true)
            }
        }
        onNodeWithText("Overview").assertIsDisplayed()
        onNodeWithContentDescription("Overview text").assertIsDisplayed()
    }

    @Test
    fun settingsOpensTheNoticesInPlaceAndTheKeyboardScrollsThem() = runComposeUiTest {
        setContent {
            KoneTheme(false) {
                SettingsView(SettingsState(Appearance.System, Backdrop.Ink, autostart = false, hotkey = true, version = "1.0.0", update = UpdateState.Latest), SettingsActions())
            }
        }
        onNodeWithText("Overview").assertDoesNotExist()
        // The last line of a page longer than the window: brought into view first, as a person would scroll to it.
        onNodeWithContentDescription("Show third-party notices").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performScrollTo().performClick()
        waitForIdle()
        // The overview first, then any text by its tab.
        onNodeWithText("Overview").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
        onNodeWithText("KOne for Windows: third-party software and data", substring = true).assertExists()
        onNodeWithText("GPL-3.0").performScrollTo().performClick()
        onNodeWithText("GPL-3.0").assertIsSelected()
        onNodeWithText("Overview").assertIsNotSelected()
        val frame = onNodeWithContentDescription("GPL-3.0 text")
        val text = onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true)
        // How far the text has scrolled within its frame, and how far it can.
        val range = { text.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange] }
        fun scrolled() = range().value()
        val top = scrolled()
        // Reached by the keyboard, the frame scrolls: a page down, to the end, and home again.
        frame.requestFocus()
        frame.performKeyInput { pressKey(Key.PageDown) }
        waitForIdle()
        val paged = scrolled()
        assertTrue(paged > top + 100, "Page Down did not scroll the text ($top to $paged)")
        frame.performKeyInput { pressKey(Key.MoveEnd) }
        waitForIdle()
        assertEquals(range().maxValue(), scrolled(), 1f, "End did not scroll to the end")
        frame.performKeyInput { pressKey(Key.MoveHome) }
        waitForIdle()
        assertEquals(top, scrolled(), 1f)
        // Presses quicker than the text moves add up: three lines each, as a held arrow sends them.
        frame.performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        assertEquals(top + 3 * with(density) { 54.sp.roundToPx() }, scrolled(), 1f)
        // And closed again.
        onNodeWithContentDescription("Hide third-party notices").performScrollTo().performClick()
        onNodeWithText("Overview").assertDoesNotExist()
    }
}
