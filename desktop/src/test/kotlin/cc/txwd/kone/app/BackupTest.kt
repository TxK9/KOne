package cc.txwd.kone.app

import cc.txwd.kone.core.UserRule
import cc.txwd.kone.node.SubscriptionContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BackupTest {
    @Test
    fun aBackupReadsBackWhole() {
        val backup = Backup(
            mode = "Global", way = "Proxy", bypass = listOf("Steam.exe"), direct = listOf("example.cn"), proxied = listOf("example.com"),
            rules = listOf(UserRule("keyword", "tracker", "block")), blockAds = true, remoteDns = "google", directDns = "203.0.113.53",
            ipv6 = true, failover = false, subscriptions = listOf(Triple("Example Cloud", "https://example.com/sub?token=t", 24)),
            imported = SubscriptionContent.read("trojan://pw@203.0.113.1:443#Tokyo").nodes,
        )
        val text = backup.write()
        assertEquals(backup, Backup.read(text))
        assertFalse(text.contains("credential"), "nothing of the device's binding")
    }

    @Test
    fun anythingElseIsNotABackup() {
        assertNull(Backup.read("{}"))
        assertNull(Backup.read("""{"kone": "backup", "version": 2}"""))
        assertNull(Backup.read("not json"))
        assertEquals(Backup(), Backup.read("""{"kone": "backup", "version": 1}"""), "a backup of nothing restores nothing")
    }
}
