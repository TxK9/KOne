package cc.txwd.kone.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkReachTest {
    /** A network on this computer: [answer] decides what each path gives. */
    private fun network(answer: (path: String) -> Triple<Int, String, String?>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (status, body, location) = answer(exchange.requestURI.path)
                location?.let { exchange.responseHeaders.add("Location", it) }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
            start()
        }

    private fun targets(server: HttpServer) = listOf(
        NetworkReach.Target("http://127.0.0.1:${server.address.port}/trace") { status, body -> status == 200 && "fl=" in body },
        NetworkReach.Target("http://127.0.0.1:${server.address.port}/resolve") { status, body -> status == 200 && body.startsWith("{") },
    )

    private fun checked(answer: (String) -> Triple<Int, String, String?>): ReachCheck {
        val server = network(answer)
        try {
            return NetworkReach.check(targets = targets(server), timeoutMillis = 2_000)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anOpenNetworkAnswersAsItAlwaysDoes() {
        assertEquals(ReachCheck(Reach.Open), checked { path -> if (path == "/trace") Triple(200, "fl=1\nh=1.1.1.1", null) else Triple(200, "{\"Status\":0}", null) })
        // One usual answer is enough: the other address may be blocked where the person is.
        assertEquals(ReachCheck(Reach.Open), checked { path -> if (path == "/trace") Triple(503, "", null) else Triple(200, "{\"Status\":0}", null) })
    }

    @Test
    fun aNetworkThatRedirectsToItsPageWantsASignIn() {
        val portal = "http://portal.example.net/login?from=kone"
        assertEquals(ReachCheck(Reach.SignIn, portal), checked { Triple(302, "", portal) })
        // A login page served in place of the answer, with no address to go to.
        assertEquals(ReachCheck(Reach.SignIn, null), checked { Triple(200, "<html>Welcome, please sign in</html>", null) })
        // Only an http or https page is ever opened.
        assertEquals(ReachCheck(Reach.SignIn, null), checked { Triple(302, "", "javascript:alert(1)") })
        assertEquals(ReachCheck(Reach.SignIn, null), checked { Triple(302, "", "file:///C:/Windows/win.ini") })
    }

    @Test
    fun aNetworkThatAnswersNothingIsOffline() {
        val closed = ServerSocket(0).use { it.localPort }
        val targets = listOf(
            NetworkReach.Target("http://127.0.0.1:$closed/trace") { _, _ -> true },
            NetworkReach.Target("http://127.0.0.1:$closed/resolve") { _, _ -> true },
        )
        assertEquals(ReachCheck(Reach.Offline), NetworkReach.check(targets = targets, timeoutMillis = 1_000))
    }

    @Test
    fun theVerdictWeighsEveryAnswer() {
        val usual = NetworkReach.Heard.Usual
        val nothing = NetworkReach.Heard.Nothing
        assertEquals(Reach.Open, NetworkReach.verdict(listOf(nothing, usual)).reach)
        assertEquals(Reach.Offline, NetworkReach.verdict(listOf(nothing, nothing)).reach)
        assertEquals(ReachCheck(Reach.SignIn, "https://login.example.org/"), NetworkReach.verdict(listOf(nothing, NetworkReach.Heard.Other("https://login.example.org/"))))
    }

    /** The real addresses answer as KOne expects on an open network (-Pkone.live=true; CI's runners). */
    @Test
    fun theRealAddressesAnswerAsExpected() {
        assumeTrue(System.getProperty("kone.live") == "true", "asks real services (-Pkone.live=true)")
        for (target in NetworkReach.TARGETS) {
            assertEquals(ReachCheck(Reach.Open), NetworkReach.check(targets = listOf(target), timeoutMillis = 8_000), target.url)
        }
    }
}
