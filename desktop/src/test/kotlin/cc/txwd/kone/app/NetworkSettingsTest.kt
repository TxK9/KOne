package cc.txwd.kone.app

import cc.txwd.kone.core.Host
import cc.txwd.kone.core.NameServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSettingsTest {
    private val preferences = Preferences(Files.createTempDirectory("kone-network").toFile())
    private var restarts = 0
    private val settings = NetworkSettings(preferences) { restarts++ }

    @Test
    fun eachSettingIsCheckedKeptAndTakenByTheConnection() {
        assertTrue(settings.setRemoteDns("google"))
        assertFalse(settings.setRemoteDns("http://dns.example.com/"))
        assertTrue(settings.setDirectDns("203.0.113.53"))
        assertTrue(settings.setDirectDns("203.0.113.53")) // the same: no restart
        assertTrue(settings.addHost("192.0.2.10 nas.example.org"))
        assertFalse(settings.addHost("nas.example.org"))
        assertTrue(settings.addNameServer("corp.example.org 192.0.2.53"))
        settings.setIpv6(true)
        assertEquals(5, restarts)
        assertEquals(listOf(Host("nas.example.org", "192.0.2.10")), settings.page.hosts)
        assertEquals(listOf(NameServer("corp.example.org", "192.0.2.53")), preferences.nameServers)
        settings.removeHost(Host("nas.example.org", "192.0.2.10"))
        settings.removeNameServer(NameServer("corp.example.org", "192.0.2.53"))
        assertEquals(7, restarts)
        // Failover is the App's own doing: kept and shown, no reconnection.
        settings.setFailover(false)
        assertFalse(settings.page.failover)
        assertEquals(7, restarts)
    }
}
