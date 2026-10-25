package cc.txwd.kone.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkChangesTest {
    private val wifi = Conflicts.Adapter("wlan0", "Intel Wi-Fi 6", up = true, addresses = listOf("192.0.2.15", "fe80::1", "2001:db8::15"))
    private val cable = Conflicts.Adapter("eth0", "Realtek Ethernet", up = true, addresses = listOf("198.51.100.7"))
    private val kone = Conflicts.Adapter("KOne", "sing-tun Tunnel", up = true, addresses = listOf("172.19.0.1", "fdfe:dcba:9876::1"))
    private val loopback = Conflicts.Adapter("lo", "Loopback", up = true, addresses = listOf("127.0.0.1", "::1"))
    private val noDhcp = Conflicts.Adapter("eth1", "USB Ethernet", up = true, addresses = listOf("169.254.10.20"))

    @Test
    fun onlyTheAddressesTrafficLeavesByCount() {
        assertEquals(setOf("192.0.2.15"), NetworkChanges.addresses(listOf(wifi, kone, loopback, noDhcp)), "IPv4 only; not KOne's adapter, loopback or 169.254")
        assertEquals(emptySet(), NetworkChanges.addresses(listOf(wifi.copy(up = false))), "an adapter that is down carries nothing")
    }

    @Test
    fun koneConnectingIsNotAChangeOfNetwork() {
        val before = NetworkChanges.addresses(listOf(wifi, loopback))
        val after = NetworkChanges.addresses(listOf(wifi, loopback, kone))
        assertNull(NetworkChanges.change(before, after))
    }

    @Test
    fun aNetworkSwitchSaysWhatWasLostAndGained() {
        val before = NetworkChanges.addresses(listOf(wifi, kone))
        val after = NetworkChanges.addresses(listOf(wifi.copy(up = false), cable, kone))
        assertEquals(NetworkChanges.Change(lost = setOf("192.0.2.15"), gained = setOf("198.51.100.7")), NetworkChanges.change(before, after))
        assertEquals(NetworkChanges.Change(lost = emptySet(), gained = setOf("198.51.100.7")), NetworkChanges.change(before, before + "198.51.100.7"), "a cable plugged in beside Wi-Fi loses nothing")
    }

    @Test
    fun ipv6PrivacyAddressesTurningOverAreNotAChange() {
        val before = NetworkChanges.addresses(listOf(wifi))
        val after = NetworkChanges.addresses(listOf(wifi.copy(addresses = listOf("192.0.2.15", "fe80::1", "2001:db8::99"))))
        assertNull(NetworkChanges.change(before, after))
    }
}
