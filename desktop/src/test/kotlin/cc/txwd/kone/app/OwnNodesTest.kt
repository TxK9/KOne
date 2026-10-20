package cc.txwd.kone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
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
        override fun addSubscription(link: String): String? = null
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

    @Test
    fun qrCodesBringNodesAndSubscriptions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dashboard = mutableListOf<Pair<String, Boolean>>()
        val subscribed = mutableListOf<String>()
        val scanning = OwnNodes(scope, Imports(File(Files.createTempDirectory("qr").toFile(), "imported.bin"), Vault.Memory()), object : OwnNodes.Host {
            override val managed: JsonObject = JsonObject(mapOf("outbounds" to kotlinx.serialization.json.JsonArray(listOf(console))))
            override val selected: String? = null
            override val imported = emptyList<String>()
            override fun own() = OwnNodes.Own(listOf(console), emptySet())
            override fun nameOf(tag: String) = tag
            override fun inUse(): String? = null
            override fun accepts(source: JsonObject) = true
            override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = nodes
            override fun nodesChanged(gone: String?) {}
            override fun restart() {}
            override fun refresh() {}
            override fun tell(message: String, error: Boolean) {}
            override fun imported(message: String, good: Boolean) {
                dashboard += message to good
            }
            override fun addSubscription(link: String): String? = if (link in subscribed) "This subscription is already here" else null.also { subscribed += link }
        })
        fun scan(vararg texts: String) {
            scanning.importQr("on the screen") { texts.map(::picture) }
            // Until the reading and the import it starts are done.
            runBlocking { while (scope.coroutineContext[Job]!!.children.any { it.isActive }) scope.coroutineContext[Job]!!.children.forEach { it.join() } }
        }

        scan("trojan://example-password@node1.invalid:443?sni=example.org#Tokyo")
        assertEquals("Imported 1 node" to true, dashboard.last())
        scan("clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub")
        assertEquals(listOf("https://example.com/sub"), subscribed)
        assertEquals("Subscription added from the QR code" to true, dashboard.last())
        scan("https://example.com/sub")
        assertEquals("This subscription is already here" to false, dashboard.last())
        scan("kone://claim?key=EXAMPLE")
        assertEquals(false, dashboard.last().second)
        assertTrue(dashboard.last().first.startsWith("This QR code is a KOne key"))
        scanning.importQr("on the screen") { listOf(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)) }
        runBlocking { scope.coroutineContext[Job]!!.children.forEach { it.join() } }
        assertEquals("No QR code found on the screen" to false, dashboard.last())
    }

    /** [text] as a QR code on a white picture. */
    private fun picture(text: String): BufferedImage {
        val modules = cc.txwd.kone.ui.qrModules(text)!!
        return BufferedImage(modules.size * 5, modules.size * 5, BufferedImage.TYPE_INT_RGB).also { image ->
            image.createGraphics().apply {
                color = java.awt.Color.WHITE
                fillRect(0, 0, image.width, image.height)
                color = java.awt.Color.BLACK
                modules.forEachIndexed { row, cells -> cells.forEachIndexed { column, on -> if (on) fillRect(column * 5, row * 5, 5, 5) } }
                dispose()
            }
        }
    }
}
