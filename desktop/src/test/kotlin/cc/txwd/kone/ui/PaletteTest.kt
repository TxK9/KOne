package cc.txwd.kone.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteTest {
    private fun c(title: String, detail: String) = Command(title, detail) {}
    private val commands = listOf(
        c("Connect", "United States"), c("Use United States", "US · 245 ms"), c("Use United States 2", "US · 312 ms"),
        c("Use Japan", "JP · 186 ms"), c("Mode: Rule", "Tools"), c("Mode: Global", "Tools"), c("Go to Rules", "Page"),
    )

    @Test
    fun everyWordMustFitAndWordStartsCountForMore() {
        // Best first: the title holds both words; the other only finds "2" in its delay.
        assertEquals(listOf("Use United States 2", "Use United States"), rank(commands, "us 2").map { it.title })
        assertEquals(listOf("Use Japan"), rank(commands, "jap").map { it.title })
        // "rule" starts a word in both; the mode and the page are both offered, in the order given.
        assertEquals(listOf("Mode: Rule", "Go to Rules"), rank(commands, "rule").map { it.title })
        assertEquals(emptyList(), rank(commands, "nothing like it").map { it.title })
        // Nothing typed: the first few as given.
        assertEquals(3, rank(commands, " ", most = 3).size)
        assertEquals("Connect", rank(commands, "").first().title)
    }
}
