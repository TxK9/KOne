package cc.txwd.kone.app

import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hold a real loopback delay reply across a list update, through the public Test action. */
class AppModelContinuityTest {
    @Test
    fun oldDelayRepliesDoNotMarkAReplacementAsTested() = checkReply("Paris", "198.51.100.9", emptyMap())

    @Test
    fun oldDelayRepliesFollowAnExactlyRenamedNode() = checkReply("Paris renamed", "203.0.113.1", mapOf("Paris renamed" to 87))

    @Test
    fun manualTestsDiscardRepliesFromAReplacedProbe() = checkReply("Paris", "203.0.113.1", emptyMap(), probing = true)

    private fun checkReply(name: String, server: String, expected: Map<String, Int?>, probing: Boolean = false) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val folder = Files.createTempDirectory("kone-pending-delay").toFile()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val core = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        core.createContext("/") { exchange ->
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            val reply = """{"delay":87}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        core.start()
        try {
            val model = AppModel(scope, Preferences(folder))
            val continuity = get<NodeContinuity>(model, "continuity")
            fun listing(name: String, server: String): KoneConfig.Built {
                val source = Json.parseToJsonElement("""{"outbounds":[{"type":"trojan","tag":"$name","server":"$server","server_port":443,"password":"example"}]}""") as JsonObject
                return KoneConfig.build(source, folder.path, CoreApi(9090, "example"))
            }
            val initial = listing("Paris", "203.0.113.1")
            set(model, "nodes", initial.nodes)
            continuity.listed(NodeContinuity.Listing.of(initial))
            val endpoint = CoreApi(core.address.port, "example")
            if (probing) {
                set(model, "probeApi", endpoint)
            } else {
                continuity.started(NodeContinuity.Listing.of(initial))
                set(model, "runningTags", setOf("Paris"))
                set(model, "clash", ClashApi(endpoint) { continuity.running })
            }
            model.testAll()
            assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) }, "Delay request did not arrive")
            val updated = listing(name, server)
            set(model, "nodes", updated.nodes)
            continuity.listed(NodeContinuity.Listing.of(updated))
            if (probing) set(model, "probeApi", null)
            release.countDown()
            withTimeout(5_000) { while (model.nodesPage.testing) delay(10) }
            assertEquals(expected, get<Map<String, Int?>>(model, "delays"))
            val health = get<Health>(model, "health")
            assertEquals(expected[name]?.let { listOf<Int?>(it) }.orEmpty(), health.samples(name))
            assertEquals(expected.containsKey(name), model.nodesPage.sections.single().nodes.single().tested)
        } finally {
            release.countDown()
            scope.cancel()
            core.stop(0)
            folder.deleteRecursively()
        }
    }

    // Fixture setup only: no real account, installed core, service or external network is used.
    private fun set(model: AppModel, name: String, value: Any?) = AppModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        set(model, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(model: AppModel, name: String): T = AppModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(model) as T
    }
}
