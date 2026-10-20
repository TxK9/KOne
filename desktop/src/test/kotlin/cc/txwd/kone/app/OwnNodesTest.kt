package cc.txwd.kone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnNodesTest {
    private fun node(tag: String, server: String) = JsonObject(
        mapOf(
            "type" to JsonPrimitive("trojan"), "tag" to JsonPrimitive(tag), "server" to JsonPrimitive(server),
            "server_port" to JsonPrimitive(443), "password" to JsonPrimitive("example-password"),
            "tls" to JsonObject(mapOf("enabled" to JsonPrimitive(true), "server_name" to JsonPrimitive("example.org"))),
        ),
    )

    private val said = mutableListOf<Pair<String, Boolean>>()
    private val console = node("Console Tokyo", "203.0.113.10")
    private val mine = node("My node", "198.51.100.20")
    private val imports = Imports(File(Files.createTempDirectory("own").toFile(), "imported.bin"), Vault.Memory())

    private val nodes = OwnNodes(CoroutineScope(Dispatchers.Unconfined), imports, object : OwnNodes.Host {
        override val managed: JsonObject? = null
        override val selected: String? = null
        override val imported = listOf("My node")
        override fun own() = OwnNodes.Own(listOf(console, mine), setOf("My node"))
        override fun nameOf(tag: String) = tag
        override fun inUse(): String? = null
        override fun accepts(source: JsonObject) = true
        override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = nodes
        override fun nodesChanged(gone: String?) {}
        override fun restart() {}
        override fun refresh() {}
        override fun tell(message: String, error: Boolean) {
            said += message to error
        }
        override fun imported(message: String, good: Boolean) {}
    })

    @Test
    fun theConsolesNodesAreNeverWrittenOut() {
        val exported = nodes.exportLinks()
        assertTrue(exported.startsWith("trojan://") && "198.51.100.20" in exported, exported)
        assertTrue("203.0.113.10" !in exported)

        var copied: String? = null
        nodes.copyLink("Console Tokyo") { copied = it }
        assertNull(copied)
        nodes.showQr("Console Tokyo")
        assertNull(nodes.qr)
        // Nothing said either: the console's nodes offer no such action.
        assertEquals(emptyList(), said)

        nodes.copyLink("My node") { copied = it }
        assertTrue(copied!!.startsWith("trojan://"))
        assertEquals(listOf("Link copied" to false), said)
        nodes.showQr("My node")
        assertEquals("My node", nodes.qr?.name)
    }
}
