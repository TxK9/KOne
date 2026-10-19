package cc.txwd.kone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionUpdatesTest {
    private val store = Subscriptions(File(Files.createTempDirectory("kone-subs").toFile(), "subscriptions.bin"), Vault.Memory())
    private val told = mutableListOf<String>()
    private var restarts = 0
    private var inUse: String? = null
    private val feeds = SubscriptionUpdates(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), store, object : SubscriptionUpdates.Host {
        // No key here, so nothing is fetched: only the bookkeeping is under test.
        override val managed: JsonObject? get() = null
        override val proxied: Int? get() = null
        override val inUse get() = this@SubscriptionUpdatesTest.inUse
        override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = nodes
        override fun refresh() {}
        override fun nodesChanged() {}
        override fun reload() {}
        override fun restart() {
            restarts++
        }
        override fun tell(message: String, error: Boolean) {
            told += message
        }
        override fun notice(text: String) {}
    })

    @Test
    fun subscriptionsAreAddedEditedAndRemovedAsAsked() {
        assertFalse(feeds.add("http://example.com/sub"), "https only")
        assertTrue(feeds.add(" https://example.com/sub "))
        assertTrue(feeds.add("https://example.com/sub"))
        assertEquals(listOf("This subscription is already here"), told)
        val id = store.all.single().id
        assertFalse(feeds.edit(id, "Example", "https://example.com/sub", hours = 9_999))
        assertTrue(feeds.edit(id, "Example", "https://example.com/sub", hours = 6, leaveOut = " 0.1x ,test"))
        assertEquals("0.1x | test", store.all.single().leaveOut)
        // Removing the one in use starts the connection afresh on what is left.
        inUse = id
        feeds.remove(id)
        assertEquals(emptyList(), store.all)
        assertEquals(1, restarts)
    }
}
