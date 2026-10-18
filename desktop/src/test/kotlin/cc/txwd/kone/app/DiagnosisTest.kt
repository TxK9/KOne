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
