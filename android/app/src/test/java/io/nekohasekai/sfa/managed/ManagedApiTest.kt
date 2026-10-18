package io.nekohasekai.sfa.managed

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** The requests the App sends and how it reads each answer, against a stand-in console. */
class ManagedApiTest {
    private lateinit var server: ServerSocket
    private val seen = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String?, String>>())
    private val replies = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()

    /** Just enough HTTP/1.1 for the App's requests: one request per connection, then close. */
    @Before
    fun start() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use {
                    val input = socket.getInputStream()
                    // Headers end at the first blank line; the body is exactly Content-Length bytes.
                    val head = java.io.ByteArrayOutputStream()
                    while (!head.toString(Charsets.UTF_8.name()).endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) break
                        head.write(next)
                    }
                    val lines = head.toString(Charsets.UTF_8.name()).split("\r\n")
                    val request = lines.firstOrNull() ?: return@use
                    var length = 0
                    var auth: String? = null
                    lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
                        val name = line.substringBefore(':').trim().lowercase()
                        val value = line.substringAfter(':').trim()
                        if (name == "content-length") length = value.toInt()
                        if (name == "authorization") auth = value
                    }
                    val bodyBytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(bodyBytes, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val body = bodyBytes.decodeToString()
                    val (method, path) = request.split(' ').let { it[0] to it[1] }
                    seen += Triple("$method $path", auth, body)
                    val (status, text) = replies[path] ?: (404 to """{"error":"\u4e0d\u5b58\u5728"}""")
                    val bytes = text.toByteArray()
                    val reply = "HTTP/1.1 $status X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(reply.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun api() = ManagedApi(ManagedEndpoint.parse("http://127.0.0.1:${server.localPort}")!!, 3_000)

    @Test
    fun claimSendsTheCodeAndReadsTheCredential() {
        replies["/api/client/claim"] = 200 to """{"deviceId":"d-1","credential":"secret-credential","format":1}"""
        val claim = api().claim("invite-code-123", "KOne · Test")
        assertEquals("d-1", claim.deviceId)
        assertEquals("secret-credential", claim.credential)
        val (call, auth, body) = seen.single()
        assertEquals("POST /api/client/claim", call)
        assertNull(auth)
        assertEquals("invite-code-123", JSONObject(body).getString("inviteCode"))
    }

    @Test
    fun claimFailuresHaveTheirOwnMeaning() {
        replies["/api/client/claim"] = 401 to """{"error":"\u9886\u53d6\u7801\u5df2\u5931\u6548"}"""
        expect(ManagedFailure.InviteInvalid) { api().claim("x".repeat(20), "d") }
        replies["/api/client/claim"] = 409 to """{"error":"\u5df2\u8fbe\u5230\u8bbe\u5907\u7ed1\u5b9a\u4e0a\u9650"}"""
        expect(ManagedFailure.DeviceLimit) { api().claim("x".repeat(20), "d") }
    }

    @Test
    fun configIsFetchedWithTheBearerCredentialOnly() {
        replies["/api/client/config"] = 200 to """{"format":1,"configKind":"sing-box-source","configName":"\u7ebf\u8def A","groupName":"\u9ed8\u8ba4",
            "revision":"r1","configRevision":"c1","config":{"outbounds":[{"type":"direct","tag":"demo"}]},
            "policy":{"quotaBytes":107374182400,"deviceLimit":2,"expiresAt":"2026-12-01T00:00:00Z","period":"2026-10"}}"""
        val delivery = api().config("secret-credential")
        assertEquals("r1", delivery.revision)
        assertEquals("c1", delivery.configRevision)
        assertEquals(107374182400L, delivery.policy.quotaBytes)
        assertEquals("2026-12-01T00:00:00Z", delivery.policy.expiresAt)
        val (call, auth, _) = seen.single()
        assertEquals("GET /api/client/config", call)
        assertEquals("Bearer secret-credential", auth)
        assertTrue(seen.none { it.first.contains("secret") })
    }

    @Test
    fun configRefusalsMapToStatesAndKeepTheConsolesWords() {
        replies["/api/client/config"] = 401 to """{"error":"\u8bbe\u5907\u51ed\u636e\u5df2\u64a4\u9500"}"""
        expect(ManagedFailure.Revoked) { api().config("c") }
        replies["/api/client/config"] = 403 to """{"error":"\u4f7f\u7528\u671f\u5df2\u7ed3\u675f"}"""
        val refused = expect(ManagedFailure.Refused) { api().config("c") }
        assertEquals("\u4f7f\u7528\u671f\u5df2\u7ed3\u675f", refused.shown)
        replies["/api/client/config"] = 409 to """{"error":"\u5c1a\u672a\u5206\u914d\u914d\u7f6e"}"""
        expect(ManagedFailure.Waiting) { api().config("c") }
        replies["/api/client/config"] = 200 to """{"format":1,"configKind":"something-else","revision":"r","config":{}}"""
        expect(ManagedFailure.ConfigInvalid) { api().config("c") }
    }

    @Test
    fun aStaleAckIsNotAnErrorButARevokedOneIs() {
        replies["/api/client/ack"] = 409 to """{"error":"\u914d\u7f6e\u7248\u672c\u5df2\u6539\u53d8，\u8bf7\u91cd\u65b0\u9886\u53d6"}"""
        api().ack("c", "r1", ManagedReport.APPLIED)
        assertEquals("applied", JSONObject(seen.single().third).getString("status"))
        replies["/api/client/ack"] = 401 to """{"error":"\u8bbe\u5907\u8ba4\u8bc1\u5931\u8d25"}"""
        expect(ManagedFailure.Revoked) { api().ack("c", "r1", ManagedReport.RECEIVED) }
    }

    @Test
    fun diagnosticsSendOnlyKnownEvents() {
        replies["/api/client/diagnostics"] = 200 to """{"ok":true}"""
        api().diagnostics("c", "1.0.6 (beta)", "cellular", listOf("app_started", "visited:example.com", "connection_ok"))
        val body = JSONObject(seen.single().third)
        assertEquals(listOf("app_started", "connection_ok"), (0 until body.getJSONArray("events").length()).map { body.getJSONArray("events").getString(it) })
        assertEquals("1.0.6beta", body.getString("appVersion"))
        assertEquals("cellular", body.getString("network"))
    }

    @Test
    fun anUnreachableConsoleIsOffline() {
        val port = server.localPort
        server.close()
        expect(ManagedFailure.Offline) { ManagedApi(ManagedEndpoint.parse("http://127.0.0.1:$port")!!, 1_000).config("c") }
        start()
    }

    private fun expect(failure: ManagedFailure, block: () -> Unit): ManagedException {
        try {
            block()
        } catch (thrown: ManagedException) {
            assertEquals(failure, thrown.failure)
            return thrown
        }
        fail("expected $failure")
        throw IllegalStateException()
    }
}
