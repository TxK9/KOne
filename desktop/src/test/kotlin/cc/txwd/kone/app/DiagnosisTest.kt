package cc.txwd.kone.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagnosisTest {
    @Test
    fun theFirstFailureGetsOneSuggestion() {
        assertNull(Diagnosis.advice(listOf(Check("Key", Outcome.Pass), Check("DNS", Outcome.Warn))))
        assertEquals("Try another node, or Test all on Nodes", Diagnosis.advice(listOf(Check("Key", Outcome.Pass), Check("Node", Outcome.Fail), Check("DNS", Outcome.Fail))))
        val sites = Diagnosis.SITES.map { Check(it.first, Outcome.Fail) }
        assertEquals("Nothing answers: check the node and the mode in Tools", Diagnosis.advice(listOf(Check("Node", Outcome.Pass)) + sites))
        assertEquals("Some sites do not answer: check your Rules", Diagnosis.advice(listOf(Check("Google", Outcome.Pass), Check("GitHub", Outcome.Fail))))
        assertEquals(Outcome.Warn, Diagnosis.node("Japan", 1200).outcome)
        assertEquals("Japan did not answer", Diagnosis.node("Japan", null).detail)
    }

    @Test
    fun namesAndSitesAreCheckedForReal() = runBlocking {
        assertEquals(Outcome.Pass, Diagnosis.names(listOf("localhost")).outcome)
        assertEquals(Outcome.Fail, Diagnosis.names(listOf("nothing.invalid")).outcome)
        assertEquals(Outcome.Warn, Diagnosis.names(listOf("localhost", "nothing.invalid")).outcome)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
            start()
        }
        val closed = ServerSocket(0).use { it.localPort }
        try {
            val checks = Diagnosis.sites(null, listOf("Here" to "http://127.0.0.1:${server.address.port}/", "Gone" to "http://127.0.0.1:$closed/"))
            assertEquals(listOf(Outcome.Pass, Outcome.Fail), checks.map { it.outcome })
            assertEquals("No answer", checks[1].detail)
        } finally {
            server.stop(0)
        }
    }
}

class DiagnosisReportTest {
    @org.junit.jupiter.api.Test
    fun theReportSaysEachCheckAndLeavesTheExitOut() {
        val report = Diagnosis.report(
            listOf(Check("Network", Outcome.Pass, "Open"), Check("Node", Outcome.Fail, "Japan did not answer"), Check("Exit", Outcome.Pass, "203.0.113.45")),
            "Try another node, or Test all on Nodes",
            listOf("Version" to "1.0.41", "Mode" to "Rule"),
        )
        kotlin.test.assertEquals(
            """
            KOne diagnosis
            Version: 1.0.41
            Mode: Rule

            ok   Network   Open
            FAIL Node      Japan did not answer
            ok   Exit      (left out of the report)

            Suggestion: Try another node, or Test all on Nodes
            """.trimIndent(),
            report,
        )
        kotlin.test.assertFalse("203.0.113.45" in report)
    }

    @Test
    fun aClockFarOffIsSaidWithTheWayToFixIt() {
        assertNull(Diagnosis.clock(null))
        assertEquals(Check("Clock", Outcome.Pass, "Right"), Diagnosis.clock(-12))
        assertEquals(Check("Clock", Outcome.Warn, "45 s ahead"), Diagnosis.clock(45))
        val off = Diagnosis.clock(-7 * 60)!!
        assertEquals(Check("Clock", Outcome.Fail, "7 min behind"), off)
        assertEquals(Check("Clock", Outcome.Fail, "3 days ahead"), Diagnosis.clock(3 * 86_400 + 5))
        // Before the node: a node cannot fix the clock.
        assertEquals(
            "Windows' clock is 7 min behind: turn on Set time automatically (Settings → Time & language), then connect again",
            Diagnosis.advice(listOf(Check("Network", Outcome.Pass, "Open"), off, Check("Node", Outcome.Fail, "Japan did not answer"))),
        )
        // The skew is read from a server's HTTP Date.
        assertEquals(60L, cc.txwd.kone.core.NetworkReach.skew("Mon, 19 Oct 2026 10:00:00 GMT", now = java.time.Instant.parse("2026-10-19T10:01:00Z").toEpochMilli()))
        assertNull(cc.txwd.kone.core.NetworkReach.skew("yesterday"))
    }
}
