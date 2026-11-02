package io.nekohasekai.sfa.compose.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiSgrTest {
    private val red = 0xFFFF2158.toInt()
    private val cyan = 0xFF5DADE2.toInt()

    @Test fun coreConnectionIdUsesTheRequested256Color() {
        // Shape written by core/log/format.go: colored level, then a 38;5 connection ID.
        val line = "\u001B[36mINFO\u001B[0m[0012] [\u001B[38;5;180m3141592\u001B[0m 5ms] inbound/tun: accepted"
        val parsed = AnsiSgr.parse(line)

        assertEquals("INFO[0012] [3141592 5ms] inbound/tun: accepted", parsed.text)
        assertEquals(AnsiSgr.strip(line), parsed.text)
        val idStart = parsed.text.indexOf("3141592")
        assertEquals(
            listOf(
                AnsiRun(0, 4, AnsiTextStyle(foreground = cyan)),
                AnsiRun(idStart, idStart + 7, AnsiTextStyle(foreground = 0xFFD7AF87.toInt())),
            ),
            parsed.runs,
        )
    }

    @Test fun every256ColorIndexTheCoreCanWriteIsDecodedAsOneColor() {
        // colorForID returns 16..230; the xterm cube uses levels 0, 95, 135, 175, 215, 255.
        assertEquals(0xFF000000.toInt(), AnsiSgr.color256(16))
        assertEquals(0xFF5F87AF.toInt(), AnsiSgr.color256(67))
        assertEquals(0xFFFFFFFF.toInt(), AnsiSgr.color256(231))
        assertEquals(0xFF080808.toInt(), AnsiSgr.color256(232))
        for (index in 16..230) {
            val run = AnsiSgr.parse("\u001B[38;5;${index}mid\u001B[0m").runs.single()
            assertEquals(AnsiSgr.color256(index), run.style.foreground)
        }
    }

    @Test fun attributesAccumulateUntilResetOrCleared() {
        val parsed = AnsiSgr.parse("\u001B[1m\u001B[31mA\u001B[22mB\u001B[39mC\u001B[4mD\u001B[mE")
        assertEquals("ABCDE", parsed.text)
        assertEquals(
            listOf(
                AnsiRun(0, 1, AnsiTextStyle(foreground = red, bold = true)),
                AnsiRun(1, 2, AnsiTextStyle(foreground = red)),
                AnsiRun(3, 4, AnsiTextStyle(underline = true)),
            ),
            parsed.runs,
        )
    }

    @Test fun resetFollowedByAColorInOneEscapeKeepsTheColor() {
        val parsed = AnsiSgr.parse("\u001B[1mx\u001B[0;31my")
        assertEquals(listOf(AnsiRun(0, 1, AnsiTextStyle(bold = true)), AnsiRun(1, 2, AnsiTextStyle(foreground = red))), parsed.runs)
    }

    @Test fun trueColorAndBackgroundsDoNotShiftLaterCodes() {
        val parsed = AnsiSgr.parse("\u001B[48;5;196;38;2;1;2;3;1mx")
        assertEquals(listOf(AnsiRun(0, 1, AnsiTextStyle(foreground = 0xFF010203.toInt(), bold = true))), parsed.runs)
    }

    @Test fun malformedExtendedColorsAreIgnoredWithoutInventingAColor() {
        for (escape in listOf("38;5", "38;5;256", "38;2;1;2", "38;2;1;2;300", "38;9;1", "38")) {
            val parsed = AnsiSgr.parse("\u001B[${escape}mx")
            assertEquals(escape, "x", parsed.text)
            assertTrue(escape, parsed.runs.isEmpty())
        }
    }

    @Test fun plainTextAndEmptyStyledRangesProduceNoRuns() {
        assertEquals(AnsiText("plain", emptyList()), AnsiSgr.parse("plain"))
        assertEquals(AnsiText("ab", emptyList()), AnsiSgr.parse("a\u001B[31m\u001B[0mb"))
    }
}
