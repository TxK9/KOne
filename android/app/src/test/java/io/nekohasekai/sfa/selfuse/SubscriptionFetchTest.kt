package io.nekohasekai.sfa.selfuse

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.concurrent.thread

/** Fetching runs against a server on this machine only; no provider is ever contacted. */
class SubscriptionFetchTest {
    private val token = "example-secret-token"
    private val links = listOf(
        "trojan://example-password@node1.invalid:443?sni=example.org#Tokyo",
        "trojan://example-password@node2.invalid:443?sni=example.org#Frankfurt",
    ).joinToString("\n")
    private lateinit var server: ServerSocket
    private lateinit var base: String

    /** An answer to one path: status, headers and body. */
    private class Answer(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private val answers = mapOf(
        "/sub/$token" to Answer(200, links, mapOf("subscription-userinfo" to "upload=1; download=2; total=1073741824; expire=1900000000")),
        "/moved/$token" to Answer(302, headers = mapOf("Location" to "/sub/$token")),
        "/loop/$token" to Answer(302, headers = mapOf("Location" to "/loop/$token")),
        "/gone/$token" to Answer(404),
        "/busy/$token" to Answer(503),
        "/page/$token" to Answer(200, "<!DOCTYPE html><html><body>Sign in</body></html>"),
    )

    @Before
    fun start() {
        server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val answer = answers[path] ?: Answer(404)
                    val body = answer.body.toByteArray()
                    val head = buildString {
                        append("HTTP/1.1 ${answer.code} X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                        answer.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("\r\n")
                    }
                    it.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                }
            }
        }
        base = "http://${server.inetAddress.hostAddress}:${server.localPort}"
    }

    @After
    fun stop() = server.close()

    private fun refusal(url: String): SubscriptionException =
        runCatching { SubscriptionProfile.download(url, userAgent = "test") }.exceptionOrNull() as SubscriptionException

    @Test
    fun aMovedLinkIsFollowedAndTheProvidersTermsComeWithIt() {
        val (body, info) = SubscriptionProfile.download("  $base/moved/$token\n", userAgent = "test")
        assertEquals(links, body)
        assertEquals(1_073_741_824L, info?.total)
        assertEquals(1_900_000_000L, info?.expire)
    }

    @Test
    fun refusalsSayWhatHappenedWithoutTheLink() {
        val gone = refusal("$base/gone/$token")
        assertEquals("The provider no longer has this link (404)", gone.message)
        assertFalse(gone.temporary)
        assertTrue(refusal("$base/busy/$token").temporary)
        assertTrue(refusal("$base/page/$token").message!!.startsWith("The provider sent a web page"))
        assertEquals("The provider moved the link too many times", refusal("$base/loop/$token").message)
        assertEquals("Only http and https links can be fetched", refusal("ftp://example.com/$token").message)
        for (url in listOf("gone", "busy", "page", "loop").map { "$base/$it/$token" }) {
            assertFalse(refusal(url).message!!.contains(token))
        }
    }

    @Test
    fun movesNeverDropFromHttpsToHttp() {
        val secure = URL("https://example.com/sub/$token")
        assertEquals("https://example.net/new", SubscriptionProfile.nextHop(secure, "https://example.net/new").toString())
        assertEquals("https://example.com/other", SubscriptionProfile.nextHop(secure, "/other").toString())
        assertEquals("https://example.com/sub", SubscriptionProfile.nextHop(URL("http://example.com/sub"), "https://example.com/sub").toString())
        assertNull(SubscriptionProfile.nextHop(secure, "http://example.com/sub/$token"))
        assertNull(SubscriptionProfile.nextHop(secure, "ftp://example.com/"))
        assertNull(SubscriptionProfile.nextHop(secure, null))
        assertNull(SubscriptionProfile.nextHop(secure, " "))
    }

    @Test
    fun onlyPassingTroubleIsTriedAgainSoon() {
        assertNull(SubscriptionProfile.refusal(200))
        assertFalse(SubscriptionProfile.refusal(403)!!.temporary)
        assertFalse(SubscriptionProfile.refusal(410)!!.temporary)
        assertTrue(SubscriptionProfile.refusal(429)!!.temporary)
        assertTrue(SubscriptionProfile.refusal(502)!!.temporary)
        // The exception's own words may name the host or the link; the refusal's never do.
        val lookup = SubscriptionProfile.failure(UnknownHostException("sub.example.com"))
        assertTrue(lookup.temporary)
        assertFalse(lookup.message!!.contains("example.com"))
        assertTrue(SubscriptionProfile.failure(SocketTimeoutException()).temporary)
        assertNotNull(SubscriptionProfile.failure(IOException("$base/sub/$token")).message?.takeUnless { it.contains(token) })
    }

    @Test
    fun aWebPageIsToldApartFromASubscription() {
        assertTrue(SubscriptionProfile.isWebPage("<!doctype html><title>Just a moment</title>"))
        assertTrue(SubscriptionProfile.isWebPage("\n  <HTML><head></head></HTML>"))
        assertTrue(SubscriptionProfile.isWebPage("<div>x</div><body>login</body>"))
        assertFalse(SubscriptionProfile.isWebPage(links))
        assertFalse(SubscriptionProfile.isWebPage("""{"outbounds":[]}"""))
        assertFalse(SubscriptionProfile.isWebPage("proxies:\n  - { name: a, type: trojan }"))
    }

    @Test
    fun anEmptySubscriptionIsARefusalInFixedWords() {
        val refusal = runCatching { SubscriptionProfile.build("nothing to see", HomeLink.Home(false, emptyList(), "phone")) }.exceptionOrNull()
        assertTrue(refusal is SubscriptionException)
        assertFalse((refusal as SubscriptionException).temporary)
    }
}
