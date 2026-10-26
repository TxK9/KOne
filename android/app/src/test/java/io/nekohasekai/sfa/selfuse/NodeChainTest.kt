package io.nekohasekai.sfa.selfuse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeChainTest {
    private fun profile(node: String) = """
        {"outbounds":[
          {"type":"selector","tag":"proxy","outbounds":["node"],"default":"node"},
          $node,
          {"type":"direct","tag":"direct"}
        ]}
    """.trimIndent()

    private val exit = profile("""{"type":"vless","tag":"node","server":"203.0.113.10","server_port":443,"uuid":"00000000-0000-0000-0000-000000000000"}""")
    private val front = profile("""{"type":"trojan","tag":"node","server":"198.51.100.7","server_port":443,"password":"example"}""")

    private fun outbounds(content: String) = JSONObject(content).getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun theChosenNodeDialsThroughACopyOfTheFrontNode() {
        val chained = NodeChain.chain(exit, front, "Tokyo")!!
        val all = outbounds(chained)
        val carrier = all.single { it.optString("tag") == "Through · Tokyo" }
        assertEquals("trojan", carrier.getString("type"))
        assertEquals("198.51.100.7", carrier.getString("server"))
        assertEquals("Through · Tokyo", all.single { it.optString("tag") == "node" }.getString("detour"))
        // The copy sits with the nodes, ahead of the direct exit, and the selector still offers only the node.
        assertEquals("direct", all.last().getString("tag"))
        assertEquals(listOf("node"), all.first().getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun aFrontNodeThatIsItselfChainedIsRefused() {
        val chainedFront = profile("""{"type":"trojan","tag":"node","server":"198.51.100.7","server_port":443,"password":"example","detour":"other"}""")
        assertNull(NodeChain.chain(exit, chainedFront, "Tokyo"))
    }

    @Test fun aNodeWithItsOwnCarrierKeepsIt() {
        val own = profile("""{"type":"vless","tag":"node","server":"203.0.113.10","server_port":443,"uuid":"00000000-0000-0000-0000-000000000000","detour":"relay"}""")
        assertNull(NodeChain.chain(own, front, "Tokyo"))
    }

    @Test fun aTagAlreadyTakenGetsANumber() {
        val taken = exit.replace("{\"type\":\"direct\",\"tag\":\"direct\"}", "{\"type\":\"direct\",\"tag\":\"Through · Tokyo\"},{\"type\":\"direct\",\"tag\":\"direct\"}")
        val tags = outbounds(NodeChain.chain(taken, front, "Tokyo")!!).map { it.optString("tag") }
        assertTrue("Through · Tokyo 2" in tags)
        assertFalse(tags.count { it == "Through · Tokyo" } > 1)
    }
}
