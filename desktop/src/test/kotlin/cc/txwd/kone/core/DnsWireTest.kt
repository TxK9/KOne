package cc.txwd.kone.core

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue

class DnsWireTest {
    @Test
    fun aQueryAsksOneQuestionWithRecursion() {
        val query = DnsWire.query("www.example.com.", DnsWire.AAAA, id = 0x1234)
        val expected = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(3) + "www".toByteArray() + byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 28, 0, 1)
        assertEquals(expected.toList(), query.toList())
        assertFailsWith<IllegalArgumentException> { DnsWire.query("a..example.com", DnsWire.A) }
        assertFailsWith<IllegalArgumentException> { DnsWire.query("x".repeat(64) + ".example.com", DnsWire.A) }
    }

    @Test
    fun readsAnswersThroughCompressedNames() {
        val query = DnsWire.query("www.example.com", DnsWire.A, id = 7)
        val reply = DnsWire.read(answer(query, 0, listOf(cname("edge.example.com"), a(203, 0, 113, 9), aaaa("20010db8000000000000000000000001"))))
        assertEquals(DnsReply(0, listOf("CNAME edge.example.com", "A 203.0.113.9", "AAAA 2001:db8::1")), reply)
        assertEquals(3, DnsWire.read(answer(query, 3, emptyList())).code)
    }

    @Test
    fun aPointerLoopIsRefusedRatherThanFollowedForever() {
        val query = DnsWire.query("www.example.com", DnsWire.A, id = 7)
        // A CNAME whose data points at itself.
        val looped = answer(query, 0, emptyList()).let { base ->
            val at = base.size + 12
            base + byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, (0xc0 or (at shr 8)).toByte(), (at and 0xff).toByte())
        }.also { it[7] = 1 }
        assertFailsWith<IllegalArgumentException> { DnsWire.read(looped) }
        assertFailsWith<IndexOutOfBoundsException> { DnsWire.read(byteArrayOf(0, 1, 2)) }
    }

    @Test
    fun asksAServerOverUdpAndKeepsOnlyItsOwnAnswer() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val serving = thread {
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                server.receive(packet)
                val query = buffer.copyOf(packet.length)
                // A stray reply with another id first, which must be ignored.
                val stray = answer(query, 0, listOf(a(198, 51, 100, 1))).also { it[0] = (it[0] + 1).toByte() }
                server.send(DatagramPacket(stray, stray.size, packet.socketAddress))
                val real = answer(query, 0, listOf(a(203, 0, 113, 5)))
                server.send(DatagramPacket(real, real.size, packet.socketAddress))
            }
            val reply = DnsWire.udp("127.0.0.1", "www.example.com", DnsWire.A, port = server.localPort)
            serving.join(3000)
            assertEquals(DnsReply(0, listOf("A 203.0.113.5")), reply)
        }
    }

    @Test
    fun asksADnsOverHttpsServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dns-query") { exchange ->
                val query = exchange.requestBody.use { it.readAllBytes() }
                val body = if (exchange.requestHeaders.getFirst("Content-Type") == "application/dns-message") answer(query, 0, listOf(a(203, 0, 113, 6))) else ByteArray(0)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val reply = DnsWire.https(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}/dns-query", "www.example.com", DnsWire.A)
            assertEquals(DnsReply(0, listOf("A 203.0.113.6")), reply)
        } finally {
            server.stop(0)
        }
    }

    /** The clash_api's /dns/query as a real sing-box answers it: through its DNS rules, a refusal and a fixed answer. */
    @Test
    fun readsTheCoresOwnLookups() {
        val found = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(found != null, "no sing-box binary here (-Pkone.singbox=…, or the workflow's)")
        val api = CoreApi(ServerSocket(0).use { it.localPort }, "test-secret")
        val config = """{"log": {"level": "warn"},
            "dns": {"servers": [{"type": "udp", "tag": "nowhere", "server": "127.0.0.1", "server_port": 9}],
              "rules": [{"domain": ["blocked.example.com"], "action": "reject"},
                {"domain": ["host.example.com"], "action": "predefined", "answer": ["host.example.com. IN A 203.0.113.7", "host.example.com. IN AAAA 2001:db8::7"]}]},
            "outbounds": [{"type": "direct"}],
            "experimental": {"clash_api": {"external_controller": "127.0.0.1:${api.port}", "secret": "${api.secret}"}}}"""
        val core = ProcessBuilder(found!!.path, "run", "-c", "stdin").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        core.outputStream.use { it.write(config.toByteArray()) }
        try {
            val clash = ClashApi(api)
            repeat(50) { if (clash.answers()) return@repeat; Thread.sleep(100) }
            assertEquals(DnsReply(0, listOf("A 203.0.113.7")), clash.dnsQuery("host.example.com", "A"))
            assertEquals(DnsReply(0, listOf("AAAA 2001:db8::7")), clash.dnsQuery("host.example.com", "AAAA"))
            assertEquals(5, assertNotNull(clash.dnsQuery("blocked.example.com", "A")).code)
        } finally {
            core.destroy()
            core.waitFor(3, TimeUnit.SECONDS)
        }
    }

    companion object {
        /** A response to [query] with [code] and [records], each written with a pointer back to the question's name. */
        fun answer(query: ByteArray, code: Int, records: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(query, 0, 2)
            out.write(0x81); out.write(0x80 or code)
            out.write(0); out.write(1); out.write(0); out.write(records.size); out.write(ByteArray(4))
            out.write(query, 12, query.size - 12)
            records.forEach { out.write(byteArrayOf(0xc0.toByte(), 12)); out.write(it) }
            return out.toByteArray()
        }

        private fun record(type: Int, data: ByteArray) = byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0, 60, (data.size shr 8).toByte(), data.size.toByte()) + data

        fun a(vararg octets: Int) = record(1, octets.map { it.toByte() }.toByteArray())

        fun aaaa(hex: String) = record(28, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

        /** A CNAME under .com whose last label is a pointer back to "com" in the question (offset 12 + 4 + 8). */
        fun cname(name: String): ByteArray {
            val labels = name.split('.').dropLast(1)
            val out = ByteArrayOutputStream()
            labels.forEach { out.write(it.length); out.write(it.toByteArray()) }
            out.write(byteArrayOf(0xc0.toByte(), 24))
            return record(5, out.toByteArray())
        }
    }
}
