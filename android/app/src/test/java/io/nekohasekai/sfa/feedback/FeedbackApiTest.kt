package io.nekohasekai.sfa.feedback

import io.nekohasekai.sfa.managed.ManagedEndpoint

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The requests the App sends and how it reads each answer, against a stand-in console. */
class FeedbackApiTest {
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
                    val redirect = if (status == 302) "Location: /redirect-target\r\n" else ""
                    val reply = "HTTP/1.1 $status X\r\n${redirect}Content-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(reply.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun api() = FeedbackApi(ManagedEndpoint.parse("http://127.0.0.1:${server.localPort}")!!, "feedback-device-credential")

    @Test fun sendsFeedbackOnlyToTheBoundEndpointWithHeaderAuthentication() {
        replies["/api/client/feedback"] = 200 to """{"id":1,"number":"FB-000001","status":"new","revision":1}"""
        val payload = JSONObject().put("requestId", "test-request").put("description", "\u6d4b\u8bd5\u53cd\u9988\u6587\u5b57")
        assertEquals("FB-000001", api().submit(payload))
        val (call, auth, body) = seen.single()
        assertEquals("POST /api/client/feedback", call)
        assertEquals("Bearer feedback-device-credential", auth)
        assertEquals(payload.toString(), JSONObject(body).toString())
        assertTrue(!call.contains("credential"))
    }

    @Test fun readsPublishedAndPendingStatusesWithoutPretendingSuccess() {
        replies["/api/client/feedback"] = 200 to """{"items":[{"number":"FB-000001","description":"\u95ee\u9898\u4ecd\u5f85\u53d1\u5e03\u4fee\u590d\u7248\u672c","status":"ready","reply":"\u4fee\u590d\u5df2\u6d4b\u8bd5","fixedVersion":""}]}"""
        val item = api().history().single()
        assertEquals("Fix awaiting release", item.statusLabel)
        assertEquals("", item.fixedVersion)
    }

    @Test fun permissionFailuresAndMissingBackendAreVisible() {
        for (code in listOf(401, 403, 404, 405, 429, 500)) {
            replies["/api/client/feedback"] = code to "{}"
            val error = runCatching { api().history() }.exceptionOrNull()
            assertTrue(error is FeedbackFailure)
            assertTrue(!error!!.message.orEmpty().contains("credential"))
        }
    }

    @Test fun refusesRedirectsInsteadOfForwardingCredentials() {
        replies["/api/client/feedback"] = 302 to "{}"
        assertTrue(runCatching { api().history() }.exceptionOrNull() is FeedbackFailure)
        assertEquals(1, seen.size)
    }

    @Test fun rejectedPayloadExplainsTheActionWithoutEchoingServerDetails() {
        replies["/api/client/feedback"] = 400 to """{"error":"\u53cd\u9988\u6587\u672c\u542b\u65e0\u6548\u5b57\u7b26"}"""
        val invalid = runCatching { api().submit(JSONObject()) }.exceptionOrNull() as FeedbackFailure
        assertEquals(400, invalid.httpStatus)
        assertEquals("The description contains unsupported characters. Edit it and try again.", invalid.message)

        val privateDetail = "private-host.example.invalid secret-node-configuration feedback-device-credential"
        replies["/api/client/feedback"] = 400 to JSONObject().put("error", privateDetail).toString()
        val unknown = runCatching { api().submit(JSONObject()) }.exceptionOrNull() as FeedbackFailure
        assertEquals("Invalid feedback. Edit it and try again.", unknown.message)
        assertTrue(!unknown.message.orEmpty().contains(privateDetail))
    }

    @Test fun distinguishesServiceAndSizeFailuresFromIncompleteSubmission() {
        for ((status, expected) in listOf(
            413 to "Feedback is too large. Remove some images and try again.",
            503 to "The feedback service is unavailable. Try again later.",
        )) {
            // Real upstream failures can be HTML rather than the console's JSON envelope.
            replies["/api/client/feedback"] = status to "<html>private upstream address</html>"
            val failure = runCatching { api().submit(JSONObject()) }.exceptionOrNull() as FeedbackFailure
            assertEquals(status, failure.httpStatus)
            assertEquals(expected, failure.message)
        }
    }

    @Test fun aSuccessStatusRequiresAValidReceiptBeforeTheDraftCanBeCleared() {
        for (body in listOf("{}", "<html>temporary gateway page</html>", """{"number":"secret-node-configuration"}""")) {
            replies["/api/client/feedback"] = 200 to body
            val failure = runCatching { api().submit(JSONObject()) }.exceptionOrNull() as FeedbackFailure
            assertEquals("No valid receipt was received. Your draft is saved. Try again.", failure.message)
        }
    }

    @Test(timeout = 5_000) fun theWholeRequestDeadlineDisconnectsAStalledImageUpload() {
        val disconnected = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://api.example.invalid")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.countDown() }
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    if (!disconnected.await(2, TimeUnit.SECONDS)) throw AssertionError("Upload was not disconnected")
                    throw IOException("private underlying connection failure")
                }
            }
        }
        val api = FeedbackApi(ManagedEndpoint.parse("https://api.example.invalid")!!,
            "feedback-device-credential", requestTimeoutMillis = 100, connectionFactory = { connection })
        val failure = runCatching { api.submit(JSONObject().put("images", "selected image data")) }.exceptionOrNull() as FeedbackFailure
        assertEquals("Submission timed out. Your draft is saved. Try again.", failure.message)
        assertEquals(0L, disconnected.count)
    }

    @Test fun malformedCredentialsFailBeforeCreatingAConnectionAndDoNotEchoTheirValue() {
        var created = 0
        val client = FeedbackApi(ManagedEndpoint.parse("https://api.example.invalid")!!,
            "synthetic\r\nX-Test: value", connectionFactory = { created++; throw AssertionError("Unexpected connection") })
        val failure = runCatching { client.history() }.exceptionOrNull()
        assertTrue("Unexpected failure type: ${failure?.javaClass?.simpleName}", failure is FeedbackFailure)
        assertTrue(!failure!!.message.orEmpty().contains("synthetic"))
        assertEquals(0, created)
    }
}
