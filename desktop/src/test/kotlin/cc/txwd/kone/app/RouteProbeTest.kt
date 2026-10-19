package cc.txwd.kone.app

import cc.txwd.kone.shared.Why
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.RouteProbe
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteProbeTest {
    /** With a real sing-box: where it sends a site, found by opening a connection through its proxy port, and said as a site check's first row. */
    @Test
    fun theRunningCoreSaysWhereASiteGoes() {
        val core = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(core != null, "no sing-box binary here (-Pkone.singbox=…, or the workflow's)")
        // A "node" that accepts and holds, so the connection stays in the core's list.
        val hold = ServerSocket(0)
        val held = mutableListOf<Socket>()
        thread(isDaemon = true) { runCatching { while (true) held += hold.accept() } }
        val api = CoreApi(ServerSocket(0).use { it.localPort }, "route")
        val mixed = ServerSocket(0).use { it.localPort }
        val config = """{"log":{"level":"error"},
            "inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":$mixed}],
            "outbounds":[{"type":"socks","tag":"Japan 02","server":"127.0.0.1","server_port":${hold.localPort}},{"type":"direct","tag":"direct"}],
            "route":{"rules":[{"domain_suffix":["example.com"],"outbound":"Japan 02"},{"domain_suffix":["example.org"],"action":"reject"}],"final":"direct"},
            "experimental":{"clash_api":{"external_controller":"127.0.0.1:${api.port}","secret":"${api.secret}"}}}"""
        val process = ProcessBuilder(core!!.path, "run", "-c", "stdin").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(config.toByteArray()) }
        val clash = ClashApi(api)
        try {
            repeat(50) { if (clash.answers()) return@repeat; Thread.sleep(100) }
            val lists = Why.Lists(emptyList(), emptyList(), listOf("example.com"))
            val found = RouteProbe.find("www.example.com", mixed, clash)
            assertEquals(Check("Now", Outcome.Pass, "Through Japan 02 · Your proxied sites"), SiteCheck.now(found, lists) { it })
            // Refused at once: never listed, and said so rather than guessed.
            assertEquals(RouteProbe.Found.Unseen, RouteProbe.find("www.example.org", mixed, clash, waitMillis = 1_000))
        } finally {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            held.forEach { it.close() }
            hold.close()
        }
    }
}
