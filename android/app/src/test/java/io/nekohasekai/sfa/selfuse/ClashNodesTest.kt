package io.nekohasekai.sfa.selfuse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashNodesTest {
    private val home = HomeLink.Home(true, listOf("192.168.1.0/24"), "phone")

    private val profile = """
        port: 7890
        proxies:
          - { name: "Tokyo 1", type: trojan, server: node1.invalid, port: 443, password: example-password, sni: example.org }
          - name: Frankfurt
            type: ss
            server: node2.invalid
            port: 8388
            cipher: aes-128-gcm
            password: example-password
          - { name: "Expire: 2026-12-31", type: trojan, server: node3.invalid, port: 443, password: example-password }
          - { name: Old, type: ssr, server: node4.invalid, port: 443 }
        rules:
          - MATCH,Proxy
    """.trimIndent()

    @Test
    fun clashProxiesBecomeNodesWithoutNotices() {
        val read = clashNodes(profile)
        assertEquals(listOf("Tokyo 1", "Frankfurt"), read.nodes.map { it.name })
        assertEquals(1, read.refused)
        assertEquals("sing-box does not support this kind of proxy.", read.refusal)
        read.nodes.forEach { assertEquals(ShareLinks.NODE_TAG, it.outbound.getString("tag")) }
        // Each one is recognised again on a second import, as a link's node is.
        assertNotNull(PairedProfile.nodeKey(read.nodes[0].outbound))
    }

    @Test
    fun aClashNodeBuildsTheSameProfileAsALink() {
        val node = clashNodes(profile).nodes[1]
        val config = JSONObject(PairedProfile.build(node.outbound, home))
        val outbounds = config.getJSONArray("outbounds")
        val built = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("tag") == ShareLinks.NODE_TAG }
        assertEquals("shadowsocks", built.getString("type"))
        assertEquals("node2.invalid", built.getString("server"))
        assertEquals(8388, built.getInt("server_port"))
    }

    @Test
    fun aProfileWithoutUsableProxiesSaysWhy() {
        assertTrue(clashNodes("proxies: []\n").nodes.isEmpty())
        assertEquals("No nodes found in the profile", clashNodes("proxies: []\n").refusal)
        assertEquals("The Clash profile cannot be read", clashNodes("proxies: [\n").refusal)
    }
}
