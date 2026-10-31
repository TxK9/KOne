package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The list's handshake with each node, on loopback: a server that answers on IPv4 alone must read
 * as reachable even when the system names an IPv6 address first, and a closed port still fails.
 */
class NodeProbeTest {
    private val v4 = InetAddress.getByName("127.0.0.1")
    private val v6 = InetAddress.getByName("::1")
    private val used = mutableListOf<Long>()

    @After
    fun clear() = used.forEach(NodeLatency::forget)

    @Test
    fun dialsTheFirstIpv4AddressThenTheFirstIpv6One() {
        val a4 = InetAddress.getByName("192.0.2.10")
        val b4 = InetAddress.getByName("198.51.100.7")
        val a6 = InetAddress.getByName("2001:db8::1")
        val b6 = InetAddress.getByName("2001:db8::2")
        assertEquals(listOf(a4, a6), dialOrder(listOf(a6, a4, b6, b4)))
        assertEquals(listOf(a6), dialOrder(listOf(a6, b6)))
        assertEquals(listOf(a4), dialOrder(listOf(a4, b4)))
        assertEquals(emptyList<InetAddress>(), dialOrder(emptyList()))
    }

    /** The phone's case: the resolver names IPv6 first, and the server answers on IPv4 alone. */
    @Test
    fun aServerAnsweringOnIpv4AloneIsReachable() {
        ServerSocket(0, 8, v4).use { server ->
            val endpoint = NodeEndpoint("node.example", server.localPort)
            // What the list used to try: the first address alone, which does not answer.
            assertNull(NodeLatency.measure(endpoint, null) { arrayOf(v6) }.ms)
            val sample = NodeLatency.measure(endpoint, null) { arrayOf(v6, v4) }
            assertNotNull("failed with ${sample.failure}", sample.ms)
        }
    }

    @Test
    fun aClosedPortStillReadsAsDown() {
        val port = ServerSocket(0, 8, v4).use { it.localPort }
        val endpoint = NodeEndpoint("node.example", port)
        val id = 9_901L.also { used += it }
        repeat(2) { NodeLatency.recordTcp(id, NodeLatency.measure(endpoint, null) { arrayOf(v6, v4) }) }
        assertEquals(NodeHealth.Down, NodeLatency[id]?.health)
        assertEquals("Connection refused", NodeLatency[id]?.label)
    }

    @Test
    fun anUnresolvableNameFailsWithoutDialling() {
        val sample = NodeLatency.measure(NodeEndpoint("node.example", 443), null) { throw java.net.UnknownHostException(it) }
        assertEquals(LatencySample(null, "No network"), sample)
    }
}
