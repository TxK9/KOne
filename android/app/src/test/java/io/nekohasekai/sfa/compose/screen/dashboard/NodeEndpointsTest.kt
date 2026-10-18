package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeEndpointsTest {
    @Test
    fun pairedProfileResolvesThroughItsSelector() {
        val link = "vless://00000000-0000-0000-0000-000000000001@node.invalid:8443?security=reality&sni=example.org&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&type=tcp"
        assertEquals(NodeEndpoint("node.invalid", 8443), NodeEndpoints.fromConfig(PairedProfile.fromLink(link, HomeLink.Home.OFF)))
    }

    @Test
    fun selectorDefaultWinsOverFirstMember() {
        val config = """
            {"outbounds":[
              {"type":"selector","tag":"pick","outbounds":["a","b"],"default":"b"},
              {"type":"trojan","tag":"a","server":"a.invalid","server_port":443},
              {"type":"vmess","tag":"b","server":"b.invalid","server_port":10086}
            ]}
        """.trimIndent()
        assertEquals(NodeEndpoint("b.invalid", 10086), NodeEndpoints.fromConfig(config))
    }

    @Test
    fun quicProxiesAndBrokenConfigsAreNotMeasured() {
        assertNull(NodeEndpoints.fromConfig("""{"outbounds":[{"type":"hysteria2","tag":"h","server":"h.invalid","server_port":443}],"route":{"final":"h"}}"""))
        assertNull(NodeEndpoints.fromConfig("""{"outbounds":[{"type":"direct","tag":"direct"}]}"""))
        assertNull(NodeEndpoints.fromConfig("""{"outbounds":[{"type":"vless","tag":"v","server":"v.invalid","server_port":0}]}"""))
        assertNull(NodeEndpoints.fromConfig("not json"))
    }
}
