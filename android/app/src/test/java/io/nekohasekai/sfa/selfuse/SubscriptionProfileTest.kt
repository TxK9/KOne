package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.ShareLinks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionProfileTest {
    private val home = HomeLink.Home(false, emptyList(), "phone")

    private fun outbounds(config: String) =
        JSONObject(config).getJSONArray("outbounds").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    @Test
    fun aProvidersLinksBecomeKonesTemplate() {
        val links = listOf(
            "trojan://example-password@node1.invalid:443?sni=example.org#Tokyo",
            "trojan://example-password@node2.invalid:443?sni=example.org#Expire%3A%202026-12-31",
            "trojan://example-password@node3.invalid:443?sni=example.org#Frankfurt",
        ).joinToString("\n")
        val body = Base64.getEncoder().encodeToString(links.toByteArray())
        assertFalse(SubscriptionProfile.isWholeConfig(body))
        val config = SubscriptionProfile.build(body, home)
        assertTrue(Routing.isTemplate(JSONObject(config)))
        val all = outbounds(config)
        // The nodes keep the provider's names; its notice is not a node.
        assertEquals("node1.invalid", all.single { it.optString("tag") == "Tokyo" }.getString("server"))
        assertTrue(all.any { it.optString("server") == "node3.invalid" })
        assertFalse(all.any { it.optString("server") == "node2.invalid" })
        val selector = all.single { it.optString("tag") == PairedProfile.PROXY_TAG }
        assertEquals(2, selector.getJSONArray("outbounds").length())
    }

    @Test
    fun aWholeConfigurationIsKeptAndAnEmptyOneIsRefusedWithAReason() {
        assertTrue(SubscriptionProfile.isWholeConfig("""{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[]}"""))
        assertFalse(SubscriptionProfile.isWholeConfig("""{"outbounds":[{"type":"trojan","tag":"a","server":"node.invalid","server_port":443}]}"""))
        val refusal = runCatching { SubscriptionProfile.build("nothing to see", home) }.exceptionOrNull()
        assertEquals("No nodes found in the subscription", refusal?.message)
    }

    @Test
    fun wordsLeaveNodesOutAndSayWhenNothingIsLeft() {
        val links = listOf(
            "trojan://example-password@node1.invalid:443?sni=example.org#Tokyo",
            "trojan://example-password@node2.invalid:443?sni=example.org#Trial Frankfurt",
        ).joinToString("\n")
        val config = SubscriptionProfile.build(links, home, leaveOut = "trial")
        assertFalse(outbounds(config).any { it.optString("server") == "node2.invalid" })
        assertTrue(outbounds(config).any { it.optString("server") == "node1.invalid" })
        val refusal = runCatching { SubscriptionProfile.build(links, home, leaveOut = "Tokyo | Trial") }.exceptionOrNull()
        assertEquals("Every node is left out by the words set for it", refusal?.message)
    }
}
