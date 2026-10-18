package cc.txwd.kone.app

import cc.txwd.kone.console.FakeConsole
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.node.ClashProxiesTest
import cc.txwd.kone.node.SubscriptionContent
import cc.txwd.kone.node.UserInfo
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionsTest {
    private val folder = Files.createTempDirectory("subscriptions").toFile()
    private val vault = Vault.Memory()
    private val hour = Subscription.HOUR

    private fun JsonObject.tag() = this["tag"]!!.jsonPrimitive.content

    @Test
    fun keptSealedOncePerLinkAndChangedInPlace() {
        val file = File(folder, "subscriptions.bin")
        val store = Subscriptions(file, vault)
        val added = store.add("https://example.com/sub?token=secret-token", "example.com")!!
        assertNull(store.add("https://example.com/sub?token=secret-token", "again"), "the same link is kept once")
        val nodes = SubscriptionContent.read("trojan://pw@203.0.113.1:443#A").nodes
        store.change(added.id) { it.copy(nodes = nodes, info = UserInfo(1, 2, 3, 4), updated = 5, tried = 5) }
        assertFalse(file.readText().contains("secret-token"), "nothing in the clear")
        val again = Subscriptions(file, vault).all.single()
        assertEquals(added.copy(nodes = nodes, info = UserInfo(1, 2, 3, 4), updated = 5, tried = 5), again)
        assertTrue(store.remove(added.id))
        assertTrue(Subscriptions(file, vault).all.isEmpty())
    }

    @Test
    fun anUpdateIsDueAfterItsIntervalAndAFailureIsTriedAgainHourly() {
        val fresh = Subscription("a", "A", "https://example.com/s", hours = 12, updated = 0, tried = 0)
        assertTrue(fresh.due(1), "never fetched")
        val good = fresh.copy(updated = 100 * hour, tried = 100 * hour)
        assertFalse(good.due(111 * hour))
        assertTrue(good.due(112 * hour))
        val failed = good.copy(tried = 112 * hour)
        assertFalse(failed.due(112 * hour + hour / 2), "not again within the hour")
        assertTrue(failed.due(113 * hour))
        assertFalse(fresh.copy(hours = 0).due(Long.MAX_VALUE / 2), "manual only")
    }

    @Test
    fun groupsJoinAfterTheManagedNodesWithTheirChainsFollowingRenames() {
        val source = FakeConsole.source(blockClaude = true)
        val imported = SubscriptionContent.read("trojan://pw@203.0.113.1:443#Osaka").nodes
        val subscribed = SubscriptionContent.read(
            """{"outbounds": [
                {"type": "trojan", "tag": "Osaka", "server": "203.0.113.2", "server_port": 443, "password": "x", "detour": "relay"},
                {"type": "trojan", "tag": "relay", "server": "203.0.113.3", "server_port": 443, "password": "y"}]}""",
        ).nodes
        val merged = Merge.into(source, listOf(imported, subscribed))
        assertEquals(listOf("Osaka"), merged.groups[0])
        assertEquals(listOf("Osaka 2", "relay"), merged.groups[1])
        val outbounds = (merged.source["outbounds"] as JsonArray).map { it as JsonObject }
        assertEquals("relay", outbounds.first { it.tag() == "Osaka 2" }["detour"]!!.jsonPrimitive.content)
        val built = KoneConfig.build(merged.source, "/r", CoreApi(9090, "s"))
        assertTrue(built.nodes.map { it.tag }.containsAll(listOf("Osaka", "Osaka 2")))
        assertFalse(built.nodes.any { it.tag == "relay" }, "a node others go through is not offered itself")
    }

    @Test
    fun fetchedOverHttpsWithWhatTheProviderSays() {
        val keystore = FakeConsole.makeKeystore()
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply {
                init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, FakeConsole.PASSWORD) }.keyManagers, null, null)
            })
            createContext("/sub") { exchange ->
                exchange.responseHeaders.add("subscription-userinfo", "upload=1; download=2; total=1000; expire=2000000000")
                exchange.responseHeaders.add("content-disposition", "attachment; filename*=UTF-8''My%20Provider.yaml")
                exchange.responseHeaders.add("profile-update-interval", "24")
                val body = "trojan://pw@203.0.113.1:443#A".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/big") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out -> repeat(9 * 1024) { out.write(ByteArray(1024)) } }
            }
            createContext("/gone") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
            start()
        }
        val tls = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keystore) }.trustManagers, null)
        }
        try {
            val base = "https://127.0.0.1:${server.address.port}"
            val fetched = SubscriptionFetch.fetch("$base/sub", tls = tls)
            assertEquals(UserInfo(1, 2, 1000, 2000000000), fetched.info)
            assertEquals("My Provider", fetched.name)
            assertEquals(24, fetched.hours)
            assertEquals("A", SubscriptionContent.read(fetched.body).nodes.single().tag())
            assertEquals("The subscription is too large", assertFailsWith<FetchException> { SubscriptionFetch.fetch("$base/big", tls = tls) }.message)
            assertEquals("The provider answered 404", assertFailsWith<FetchException> { SubscriptionFetch.fetch("$base/gone", tls = tls) }.message)
            assertEquals("Use an https:// subscription link", SubscriptionFetch.reason(assertFailsWith<FetchException> { SubscriptionFetch.fetch("http://127.0.0.1/sub") }))
            assertEquals("The provider's certificate is not trusted", SubscriptionFetch.reason(runCatching { SubscriptionFetch.fetch("$base/sub") }.exceptionOrNull()!!))
        } finally {
            server.stop(0)
        }
    }

    /** What a provider sends, Clash or sing-box, comes out as nodes sing-box itself accepts. */
    @Test
    fun theCoreAcceptsEverySubscribedKind() {
        val clash = SubscriptionContent.read(ClashProxiesTest().profile).nodes
        val singBox = SubscriptionContent.read(
            """{"outbounds": [{"type": "trojan", "tag": "a", "server": "203.0.113.5", "server_port": 443, "password": "x", "detour": "b"},
                {"type": "shadowsocks", "tag": "b", "server": "203.0.113.6", "server_port": 8388, "method": "aes-128-gcm", "password": "x"}]}""",
        ).nodes
        val merged = Merge.into(FakeConsole.source(blockClaude = true), listOf(emptyList(), clash, singBox))
        val built = KoneConfig.build(merged.source, "/r", CoreApi(9090, "s"))
        assertEquals(clash.size + 1, built.nodes.size - 2, "every Clash node and the chained sing-box one, after the console's two")
        val core = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() } ?: return
        val file = File(folder, "config.json").apply { writeText(built.config.toString()) }
        val process = ProcessBuilder(core.path, "check", "-c", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sing-box check did not finish")
        assertEquals(0, process.exitValue(), output)
    }
}

class SubscriptionWarningTest {
    private val now = 1_800_000_000_000L
    private fun subscription(used: Long, total: Long, expire: Long?) =
        Subscription("s", "Example Cloud", "https://example.com/sub", info = cc.txwd.kone.node.UserInfo(0, used, total, expire))

    @org.junit.jupiter.api.Test
    fun theTermsRunningOutAreSaidBeforeTheNodesStop() {
        val ok = subscription(10, 100, now / 1000 + 30 * 86_400)
        kotlin.test.assertNull(ok.warning(now))
        kotlin.test.assertNull(Subscription("s", "Example Cloud", "https://example.com/sub").warning(now))
        kotlin.test.assertEquals("Example Cloud has used 92% of its data", subscription(92, 100, null).warning(now))
        kotlin.test.assertEquals("Example Cloud has used all its data", subscription(120, 100, null).warning(now))
        kotlin.test.assertEquals("Example Cloud ends in 2 days", subscription(10, 100, now / 1000 + 86_400 + 60).warning(now))
        kotlin.test.assertEquals("Example Cloud ends within a day", subscription(10, 100, now / 1000 + 3_600).warning(now))
        kotlin.test.assertEquals("Example Cloud has ended", subscription(10, 100, now / 1000 - 1).warning(now))
        // No total: no share to speak of.
        kotlin.test.assertNull(subscription(500, 0, null).warning(now))
    }
}
