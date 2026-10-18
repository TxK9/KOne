package cc.txwd.kone.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConflictsTest {
    private fun adapter(description: String, up: Boolean = true, vararg addresses: String) = Conflicts.Adapter("net7", description, up, addresses.toList())

    @Test
    fun otherVpnsAreNamedOnceAndKOnesOwnAdapterIsNot() {
        val adapters = listOf(
            adapter("Intel(R) Wi-Fi 6 AX201 160MHz", true, "192.168.1.20"),
            adapter("Tailscale Tunnel", true, "100.64.0.7"),
            adapter("WireGuard Tunnel", true, "10.8.0.2"),
            adapter("WireGuard Tunnel #2", true, "10.9.0.2"),
            // KOne's own: sing-tun's description, known by its address.
            adapter("sing-tun Tunnel", true, "172.19.0.1", "fdfe:dcba:9876::1"),
            // Another sing-box client's adapter, and a TAP adapter that is down.
            adapter("sing-tun Tunnel #2", true, "172.18.0.1"),
            adapter("TAP-Windows Adapter V9", false),
            adapter("Hyper-V Virtual Ethernet Adapter", true, "172.30.16.1"),
        )
        assertEquals(listOf("Tailscale", "WireGuard", "a sing-box client"), Conflicts.vpns(adapters))
        assertEquals(emptyList(), Conflicts.vpns(listOf(adapter("sing-tun Tunnel", true, "172.19.0.1"))))
    }

    @Test
    fun windowsProxyElsewhereIsFoundButKOnesOwnPortIsNot() {
        assertEquals("127.0.0.1:7890", Conflicts.proxyElsewhere(SystemProxy.Setting(3, "127.0.0.1:7890", null, null), own = null))
        assertNull(Conflicts.proxyElsewhere(SystemProxy.Setting(3, "127.0.0.1:20171", null, null), own = 20171))
        assertEquals("127.0.0.1:7890", Conflicts.proxyElsewhere(SystemProxy.Setting(3, "127.0.0.1:7890", null, null), own = 20171))
        assertEquals("http://example.com/proxy.pac", Conflicts.proxyElsewhere(SystemProxy.Setting(5, null, null, "http://example.com/proxy.pac"), own = null))
        // Direct, or set but empty: nothing.
        assertNull(Conflicts.proxyElsewhere(SystemProxy.Setting(1, "127.0.0.1:7890", null, null), own = null))
        assertNull(Conflicts.proxyElsewhere(SystemProxy.Setting(3, " ", null, null), own = null))
        assertNull(Conflicts.proxyElsewhere(null, own = null))
    }

    @Test
    fun theCheckSaysWhatElseIsOnAndTheAdviceComesFirstWhenItMatters() {
        val clean = Conflicts.check(listOf(adapter("Ethernet", true, "192.168.1.20")), SystemProxy.Setting(1, null, null, null), own = null)
        assertEquals(Check(Conflicts.TITLE, Outcome.Pass, "No other VPN or proxy"), clean)
        val busy = Conflicts.check(listOf(adapter("Tailscale Tunnel", true, "100.64.0.7")), SystemProxy.Setting(3, "127.0.0.1:7890", null, null), own = null)
        assertEquals(Check(Conflicts.TITLE, Outcome.Warn, "Tailscale, Windows' proxy at 127.0.0.1:7890"), busy)

        // Another VPN on and sites failing: the VPN is named first.
        assertEquals(
            "Turn off Tailscale, Windows' proxy at 127.0.0.1:7890 while KOne is on: they can take traffic before it",
            Diagnosis.advice(listOf(Check("Network", Outcome.Pass), busy, Check("Node", Outcome.Fail))),
        )
        // The network itself failing still comes first.
        assertEquals(
            "Sign in to this network: Sign in on the dashboard opens its page",
            Diagnosis.advice(listOf(Check("Network", Outcome.Fail, Diagnosis.SIGN_IN), busy)),
        )
        assertEquals("This computer is offline: check Wi-Fi or the cable", Diagnosis.advice(listOf(Check("Network", Outcome.Fail, "Nothing answers"))))
    }
}
