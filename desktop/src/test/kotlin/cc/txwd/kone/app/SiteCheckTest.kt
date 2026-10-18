package cc.txwd.kone.app

import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SiteCheckTest {
    @Test
    fun theWayThatAnswersOrIsClearlyFasterIsTheOneToKeep() {
        assertEquals(SiteCheck.Way.Neither, SiteCheck.way(null, listOf(null, null)))
        assertEquals(SiteCheck.Way.Neither, SiteCheck.way(null, emptyList()))
        assertEquals(SiteCheck.Way.Proxy, SiteCheck.way(null, listOf(null, 320)))
        assertEquals(SiteCheck.Way.Direct, SiteCheck.way(45, listOf(null, null)))
        assertEquals(SiteCheck.Way.Direct, SiteCheck.way(45, listOf(320, 280)))
        assertEquals(SiteCheck.Way.Proxy, SiteCheck.way(900, listOf(400, null)))
        // Close, or a fifth apart but by little: either; nothing to change.
        assertEquals(SiteCheck.Way.Either, SiteCheck.way(300, listOf(280)))
        assertEquals(SiteCheck.Way.Either, SiteCheck.way(40, listOf(60)))
    }

    @Test
    fun eachTryIsARowAndTheVerdictIsASentence() {
        val rows = SiteCheck.rows(listOf(SiteCheck.Try("direct", "Straight out", 45), SiteCheck.Try("jp", "Japan", null), SiteCheck.Try("us", "United States", 3_400)))
        assertEquals(listOf(Check("Straight out", Outcome.Pass, "45 ms"), Check("Japan", Outcome.Fail, "No answer"), Check("United States", Outcome.Warn, "3400 ms")), rows)
        assertEquals("example.com works best straight out", SiteCheck.verdict("example.com", SiteCheck.Way.Direct))
        assertEquals("https://example.com/", SiteCheck.url("example.com"))
    }

    /** With a real sing-box and the internet (-Pkone.live=true; CI): a real site answers straight out, a dead node does not. */
    @Test
    fun aRealSiteIsTriedThroughTheCoresWays() {
        val core = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(core != null && System.getProperty("kone.live") == "true", "needs sing-box and the internet (-Pkone.live=true)")
        val api = CoreApi(ServerSocket(0).use { it.localPort }, "site-check")
        val dead = ServerSocket(0).use { it.localPort }
        val config = """{"log":{"level":"error"},"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"Dead","server":"127.0.0.1","server_port":$dead}],
            "experimental":{"clash_api":{"external_controller":"127.0.0.1:${api.port}","secret":"${api.secret}"}}}"""
        val process = ProcessBuilder(core!!.path, "run", "-c", "stdin").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(config.toByteArray()) }
        try {
            val clash = ClashApi(api)
            repeat(50) { if (clash.answers()) return@repeat; Thread.sleep(100) }
            assertNotNull(clash.delay("direct", 8_000, SiteCheck.url("www.example.com")), "www.example.com straight out")
            assertNull(clash.delay("Dead", 3_000, SiteCheck.url("www.example.com")), "through a node that is not there")
        } finally {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
        }
    }
}
