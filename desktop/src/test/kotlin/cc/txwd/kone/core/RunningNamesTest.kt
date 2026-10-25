package cc.txwd.kone.core

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A core started before an update knows the nodes by their old tags: KOne asks it in those, and
 * reads its answers back in the tags the App lists now. A stand-in for the core's clash_api on
 * loopback answers for the nodes it was started with only.
 */
class RunningNamesTest {
    private val names = RunningNames(mapOf("JP 01 | 1.5x" to "JP 01 | 1.0x", "Tokyo" to "Tokyo 2", "Tokyo 2" to "Tokyo", "Seoul" to null))

    @Test
    fun theAppsTagsAndTheCoresNamesTurnIntoEachOther() {
        assertEquals("JP 01 | 1.0x", names.core("JP 01 | 1.5x"))
        assertEquals("Tokyo 2", names.core("Tokyo"))
        assertEquals("Tokyo", names.core("Tokyo 2"))
        assertNull(names.core("Seoul"))
        assertEquals("Console", names.core("Console"))
        assertEquals(KoneConfig.AUTO_TAG, names.core(KoneConfig.AUTO_TAG))
        assertEquals("JP 01 | 1.5x", names.app("JP 01 | 1.0x"))
        assertEquals("Tokyo", names.app("Tokyo 2"))
        assertEquals("direct", names.app("direct"))
        // The core's "Seoul" is not the App's: that node is new to the list.
        assertNull(names.app("Seoul"))
        assertFalse(names.same)
        assertTrue(RunningNames.SAME.same)
    }

    @Test
    fun theRunningCoreIsAskedInItsOwnNames() {
        val carried = setOf("Console", "Tokyo", "Tokyo 2", "JP 01 | 1.0x", "Seoul")
        val asked = mutableListOf<String>()
        var selected: String? = null
        val core = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        core.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (status, body) = when {
                path.endsWith("/delay") -> {
                    val node = path.removePrefix("/proxies/").removeSuffix("/delay")
                    asked += node
                    if (node in carried) 200 to """{"delay":120}""" else 404 to """{"message":"not found"}"""
                }
                path == "/proxies/${KoneConfig.PROXY_TAG}" && exchange.requestMethod == "PUT" -> {
                    val node = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject["name"]!!.jsonPrimitive.content
                    if (node in carried) {
                        selected = node
                        204 to ""
                    } else {
                        400 to ""
                    }
                }
                path == "/proxies/${KoneConfig.AUTO_TAG}" -> 200 to """{"now":"Tokyo 2"}"""
                path == "/connections" -> 200 to """
                    {"downloadTotal":1,"uploadTotal":1,"connections":[
                      {"id":"1","metadata":{"host":"example.com","destinationPort":"443"},"chains":["JP 01 | 1.0x"],"download":1,"upload":1,"start":"2026-10-25T00:00:01Z"},
                      {"id":"2","metadata":{"host":"example.org","destinationPort":"443"},"chains":["direct"],"download":1,"upload":1,"start":"2026-10-25T00:00:00Z"}
                    ]}
                """.trimIndent()
                else -> 404 to ""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        core.start()
        try {
            val api = ClashApi(CoreApi(core.address.port, "example-secret")) { names }
            assertEquals(120, api.delay("JP 01 | 1.5x"))
            assertTrue(api.select("Tokyo"))
            assertEquals("Tokyo 2", selected)
            assertEquals("Tokyo", api.now(KoneConfig.AUTO_TAG))
            assertEquals(listOf("JP 01 | 1.5x", "direct"), api.connections()!!.connections.map { it.via })
            // A node the core does not carry is not asked for, though a node of its own once had that name.
            assertNull(api.delay("Seoul"))
            assertFalse(api.select("Seoul"))
            assertEquals(listOf("JP 01 | 1.0x"), asked)
            // A core started from the list as it is: every node by its tag.
            assertNull(ClashApi(CoreApi(core.address.port, "example-secret")).delay("JP 01 | 1.5x"))
        } finally {
            core.stop(0)
        }
    }
}
