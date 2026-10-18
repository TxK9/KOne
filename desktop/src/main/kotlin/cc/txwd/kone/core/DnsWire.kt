package cc.txwd.kone.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random

/** What a DNS server said: its response code (0 answered, 3 no such name, 5 refused) and its answers, as "A 203.0.113.1". */
data class DnsReply(val code: Int, val answers: List<String>)

/**
 * DNS messages, enough to ask a server one question and read its answers (A, AAAA, CNAME), over
 * plain UDP or DNS-over-HTTPS. For diagnosis only; KOne's own lookups are the core's.
 */
object DnsWire {
    const val A = 1
    const val AAAA = 28
    const val CNAME = 5

    /** A query for [name] of [type], with recursion asked for. */
    fun query(name: String, type: Int, id: Int = Random.nextInt(0, 0x10000)): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(value: Int) { out.write(value shr 8 and 0xff); out.write(value and 0xff) }
        short(id); short(0x0100); short(1); short(0); short(0); short(0)
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Not a domain name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        short(type); short(1)
        return out.toByteArray()
    }

    /** The response code and answers in [message]; throws on a message too short or malformed to read. */
    fun read(message: ByteArray): DnsReply {
        fun u8(at: Int) = message[at].toInt() and 0xff
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
        val code = u16(2) and 0xf
        val questions = u16(4)
        val count = u16(6)
        var at = 12
        repeat(questions) { at = skipName(message, at) + 4 }
        val answers = mutableListOf<String>()
        repeat(count) {
            at = skipName(message, at)
            val type = u16(at)
            val length = u16(at + 8)
            val data = at + 10
            when (type) {
                A -> if (length == 4) answers += "A " + message.copyOfRange(data, data + 4).joinToString(".") { (it.toInt() and 0xff).toString() }
                AAAA -> if (length == 16) answers += "AAAA " + UserRules.compressed(message.copyOfRange(data, data + 16))
                CNAME -> answers += "CNAME " + name(message, data)
            }
            at = data + length
        }
        return DnsReply(code, answers)
    }

    /** Asks [server] over UDP, waiting up to [timeoutMillis]. */
    fun udp(server: String, name: String, type: Int, timeoutMillis: Int = 3000, port: Int = 53): DnsReply {
        val id = Random.nextInt(0, 0x10000)
        val question = query(name, type, id)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.send(DatagramPacket(question, question.size, InetSocketAddress(InetAddress.getByName(server), port)))
            val buffer = ByteArray(4096)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = buffer.copyOf(packet.length)
                if (message.size >= 12 && ((message[0].toInt() and 0xff) shl 8 or (message[1].toInt() and 0xff)) == id) return read(message)
            }
        }
    }

    /** Asks a DNS-over-HTTPS server at [url] (POST, application/dns-message) with [client]. */
    fun https(client: HttpClient, url: String, name: String, type: Int, timeoutMillis: Long = 5000): DnsReply {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/dns-message").header("Accept", "application/dns-message")
            .POST(HttpRequest.BodyPublishers.ofByteArray(query(name, type, 0))).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw java.io.IOException("The server answered ${response.statusCode()}")
        return read(response.body())
    }

    private fun skipName(message: ByteArray, start: Int): Int {
        var at = start
        while (true) {
            val length = message[at].toInt() and 0xff
            if (length == 0) return at + 1
            if (length and 0xc0 == 0xc0) return at + 2
            at += length + 1
        }
    }

    /** A name, following compression pointers (a few at most, so a loop cannot hang it). */
    private fun name(message: ByteArray, start: Int): String {
        val labels = mutableListOf<String>()
        var at = start
        var jumps = 0
        while (true) {
            val length = message[at].toInt() and 0xff
            when {
                length == 0 -> return labels.joinToString(".")
                length and 0xc0 == 0xc0 -> {
                    if (++jumps > 10) throw IllegalArgumentException("Malformed name")
                    at = ((length and 0x3f) shl 8) or (message[at + 1].toInt() and 0xff)
                }
                else -> {
                    labels += String(message, at + 1, length, Charsets.US_ASCII)
                    at += length + 1
                }
            }
        }
    }
}
