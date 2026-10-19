package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.ShareLinks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLinkTest {
    private val node = ShareLinks.parse("trojan://pw@t.invalid:443#T").outbound
    private val home = HomeLink.Home(true, listOf("192.168.1.0/24"), "my-phone")

    private fun rules(config: JSONObject) = config.getJSONObject("route").getJSONArray("rules").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    private fun dnsRules(config: JSONObject) = config.getJSONObject("dns").getJSONArray("rules").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    @Test
    fun homeOnRoutesTheTailnetAndTheHomeNetworkFirstAndAnswersItsNames() {
        val config = JSONObject(PairedProfile.build(node, home))
        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("my-phone", endpoint.getString("hostname"))
        assertEquals(PairedProfile.PROXY_TAG, endpoint.getString("detour"))
        val routes = rules(config)
        assertEquals(listOf("sniff", "hijack-dns"), routes.take(2).map { it.getString("action") })
        assertEquals(PairedProfile.HOME_TAG, routes[2].getString("outbound"))
        assertEquals(HomeLink.TAILNET_RANGES + "192.168.1.0/24", strings(routes[2].getJSONArray("ip_cidr")))
        val dns = dnsRules(config)
        assertEquals(HomeLink.DNS_TAG, dns[0].getJSONArray("preferred_by").getString(0))
        assertTrue(config.getJSONObject("dns").getJSONArray("servers").toString().contains("\"accept_search_domain\":true"))
        PairedProfile.requirePaired(config.toString())
    }

    @Test
    fun homeOffLeavesOnlyTheProxy() {
        val config = JSONObject(PairedProfile.build(node, HomeLink.Home.OFF))
        assertFalse(config.has("endpoints"))
        assertFalse(config.toString().contains(PairedProfile.HOME_TAG))
        assertFalse(config.toString().contains(HomeLink.DNS_TAG))
        assertEquals(PairedProfile.PROXY_TAG, config.getJSONObject("route").getString("final"))
    }

    @Test
    fun switchingBackAndForthKeepsEverythingElseAndHandAddedEndpointFields() {
        val built = JSONObject(PairedProfile.build(node, home))
        built.getJSONArray("endpoints").getJSONObject(0).put("control_url", "https://hs.invalid")
        val renamed = JSONObject(HomeLink.apply(built.toString(), HomeLink.Home(true, emptyList(), "other")))
        val endpoint = renamed.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("other", endpoint.getString("hostname"))
        assertEquals("https://hs.invalid", endpoint.getString("control_url"))
        assertEquals(HomeLink.TAILNET_RANGES, strings(rules(renamed)[2].getJSONArray("ip_cidr")))
        assertEquals(1, rules(renamed).count { it.optString("outbound") == PairedProfile.HOME_TAG })
        val off = JSONObject(HomeLink.apply(renamed.toString(), HomeLink.Home.OFF))
        val on = JSONObject(HomeLink.apply(off.toString(), home))
        assertEquals(rules(JSONObject(PairedProfile.build(node, home))).map { it.toString().length }.sorted(), rules(on).map { it.toString().length }.sorted())
        assertEquals(dnsRules(built).size, dnsRules(on).size)
    }

    @Test
    fun configurationsWrittenElsewhereAreUntouched() {
        val other = """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[]}}"""
        assertEquals(other, HomeLink.apply(other, home))
        assertEquals("not json", HomeLink.apply("not json", home))
    }

    @Test
    fun homeNetworksAreReadForgivingly() {
        assertEquals(listOf("192.168.10.0/24", "10.0.0.1/32", "fd00::/64"), HomeLink.parseSubnets(" 192.168.10.5/24，10.0.0.1\nfd00::1/64 192.168.10.0/24"))
        assertEquals(emptyList<String>(), HomeLink.parseSubnets("  "))
        listOf("192.168.1", "nas.local", "0.0.0.0/0", "10.0.0.0/33", "300.1.1.1/24").forEach { bad ->
            val message = runCatching { HomeLink.parseSubnets("192.168.1.0/24 $bad") }.exceptionOrNull()!!.message!!
            assertTrue(message, message.contains(bad))
        }
    }

    @Test
    fun hostnamesBecomeTailnetNames() {
        assertEquals("txwd-phone-1", HomeLink.cleanHostname(" TXWD Phone_1! "))
        assertEquals("kone-pjz110", HomeLink.cleanHostname("kone-PJZ110"))
        assertEquals("", HomeLink.cleanHostname("\u624b\u673a"))
    }
}
