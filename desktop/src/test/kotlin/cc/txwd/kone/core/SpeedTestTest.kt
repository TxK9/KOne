package cc.txwd.kone.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class SpeedTestTest {
    /** Through a real sing-box with a local port, as KOne's test core runs, to a server on this computer. */
    @Test
    fun measuresBothWaysThroughACoreOfItsOwn() {
        val found = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(found != null, "no sing-box binary here (-Pkone.singbox=…, or the workflow's)")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/down") { exchange ->
                exchange.sendResponseHeaders(200, 4_000_000)
                exchange.responseBody.use { out -> repeat(4_000) { out.write(ByteArray(1_000)) } }
            }
            createContext("/up") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val port = ServerSocket(0).use { it.localPort }
        val config = """{"log": {"level": "warn"}, "inbounds": [{"type": "mixed", "listen": "127.0.0.1", "listen_port": $port}], "outbounds": [{"type": "direct"}]}"""
        val core = ProcessBuilder(found!!.path, "run", "-c", "stdin").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        core.outputStream.use { it.write(config.toByteArray()) }
        try {
            repeat(50) { if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return@repeat; Thread.sleep(100) }
            val base = "http://127.0.0.1:${server.address.port}"
            val speed = SpeedTest("$base/down", "$base/up", seconds = 5, upBytes = 2_000_000).run(port)
            assertTrue(assertNotNull(speed.down) > 1, "down ${speed.down}")
            assertTrue(assertNotNull(speed.up) > 1, "up ${speed.up}")
        } finally {
            core.destroy()
            core.waitFor(3, TimeUnit.SECONDS)
            server.stop(0)
        }
    }
}
