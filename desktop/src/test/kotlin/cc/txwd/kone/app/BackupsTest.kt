package cc.txwd.kone.app

import cc.txwd.kone.core.UserRule
import cc.txwd.kone.shared.Host
import cc.txwd.kone.ui.ToolsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupsTest {
    private fun backups(folder: File, restarts: MutableList<Unit> = mutableListOf()): Pair<Preferences, Backups> {
        val preferences = Preferences(folder)
        var tools = ToolsState(preferences.mode, preferences.bypass)
        val backups = Backups(
            CoroutineScope(Dispatchers.Unconfined), preferences,
            Subscriptions(File(folder, "subscriptions.bin"), Vault.Memory()), Imports(File(folder, "imported.bin"), Vault.Memory()),
            object : Backups.Host {
                override val tools get() = tools
                override fun update(change: (ToolsState) -> ToolsState) {
                    tools = change(tools)
                }
                override val managed: JsonObject? = null
                override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = nodes
                override fun showSettings() {}
                override fun updateLists() {}
                override fun fetch(id: String) {}
                override fun nodesChanged() {}
                override fun restart() {
                    restarts += Unit
                }
            },
        )
        return preferences to backups
    }

    @Test
    fun aBackupComesBackOnceHoweverOftenItIsRestored() {
        val (from, source) = backups(Files.createTempDirectory("kone-from").toFile())
        from.direct = listOf("example.org")
        from.rules = listOf(UserRule("suffix", "example.com", "proxy"))
        from.hosts = listOf(Host("nas.example.com", "192.0.2.10"))
        val text = source.write()

        val restarts = mutableListOf<Unit>()
        val (to, target) = backups(Files.createTempDirectory("kone-to").toFile(), restarts)
        to.direct = listOf("example.net")
        assertEquals("Backup restored", target.restore(text))
        assertEquals("Backup restored", target.restore(text))
        assertEquals(listOf("example.net", "example.org"), to.direct)
        assertEquals(listOf(UserRule("suffix", "example.com", "proxy")), to.rules)
        assertEquals(listOf(Host("nas.example.com", "192.0.2.10")), to.hosts)
        assertEquals(2, restarts.size)
        assertNull(target.restore("not a backup"))
    }
}
