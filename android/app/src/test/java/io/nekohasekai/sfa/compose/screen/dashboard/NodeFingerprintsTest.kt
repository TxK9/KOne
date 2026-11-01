package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NodeFingerprintsTest {
    // Reserved documentation addresses and a placeholder key only.
    private fun config(tag: String, server: String = "192.0.2.10", port: Int = 443, extra: String = "") = """
        {"log":{"level":"warn"},
         "outbounds":[
           {"type":"vless","tag":"$tag","server":"$server","server_port":$port,
            "uuid":"00000000-0000-4000-8000-000000000000","tls":{"enabled":true,"server_name":"node.example"}$extra},
           {"type":"direct","tag":"direct"}
         ],
         "route":{"final":"$tag"}}
    """.trimIndent()

    @Test
    fun theSameNodeUnderAnotherNameIsACopy() {
        assertEquals(NodeFingerprints.of(config("Tokyo 01")), NodeFingerprints.of(config("My node")))
    }

    @Test
    fun keyOrderDoesNotMatter() {
        val reordered = """
            {"outbounds":[{"tag":"x","tls":{"server_name":"node.example","enabled":true},"uuid":"00000000-0000-4000-8000-000000000000",
              "server_port":443,"server":"192.0.2.10","type":"vless"}]}
        """.trimIndent()
        assertEquals(NodeFingerprints.of(config("y")), NodeFingerprints.of(reordered))
    }

    @Test
    fun anotherServerPortOrSettingIsAnotherNode() {
        val base = NodeFingerprints.of(config("a"))
        assertNotNull(base)
        assertNotEquals(base, NodeFingerprints.of(config("a", server = "198.51.100.7")))
        assertNotEquals(base, NodeFingerprints.of(config("a", port = 8443)))
        assertNotEquals(base, NodeFingerprints.of(config("a", extra = ""","flow":"xtls-rprx-vision"""")))
    }

    @Test
    fun aConfigWithoutProxiesHasNoFingerprint() {
        assertNull(NodeFingerprints.of("""{"outbounds":[{"type":"direct","tag":"direct"}]}"""))
        assertNull(NodeFingerprints.of("not json"))
    }
}
