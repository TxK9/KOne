package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeIpTest {
    @Test
    fun fakeAddressesAreKnownByTheirRanges() {
        assertTrue(FakeIp.isFake("198.18.0.1"))
        assertTrue(FakeIp.isFake("198.19.255.254"))
        assertFalse(FakeIp.isFake("198.20.0.1"))
        assertFalse(FakeIp.isFake("203.0.113.1"))
        assertTrue(FakeIp.isFake("fc00::1"))
        assertTrue(FakeIp.isFake("FC00:3fff::1"))
        assertFalse(FakeIp.isFake("fc00:4000::1"))
        assertFalse(FakeIp.isFake("2001:db8::1"))
    }

    @Test
    fun onlyRulesAskingTheNodesDnsAreFaked() {
        val rules = listOf(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"domain_suffix":["example.org"],"server":"local-dns"}""") as kotlinx.serialization.json.JsonObject,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"clash_mode":"Global","server":"proxy-dns"}""") as kotlinx.serialization.json.JsonObject,
        )
        val faked = FakeIp.rules(rules, ipv6 = false)
        assertEquals(6, faked.size)
        assertEquals(rules[0], faked[0])
        // Windows' Internet check keeps its real answer, in the same mode, before the stand-in.
        assertEquals("""{"clash_mode":"Global","server":"proxy-dns","domain_suffix":["msftncsi.com","msftconnecttest.com"]}""", faked[1].toString())
        assertEquals("""{"clash_mode":"Global","server":"fake-dns","query_type":["A"]}""", faked[2].toString())
        assertEquals(rules[1], faked[3])
        assertEquals("""{"domain_suffix":["msftncsi.com","msftconnecttest.com"],"server":"proxy-dns"}""", faked[4].toString())
        assertEquals("""{"query_type":["A"],"server":"fake-dns"}""", faked[5].toString())
    }

    @Test
    fun namesThroughACountryAreFakedTooAndLookedUpThere() {
        val through = kotlinx.serialization.json.Json.parseToJsonElement("""{"domain_suffix":["example.com"],"clash_mode":"Rule","server":"proxy-dns-JP"}""") as kotlinx.serialization.json.JsonObject
        val faked = FakeIp.rules(listOf(through), ipv6 = true)
        // The twin first, then the rule itself for other query types; a rule on names has no real-name twin.
        assertEquals("""{"domain_suffix":["example.com"],"clash_mode":"Rule","server":"fake-dns","query_type":["A","AAAA"]}""", faked[0].toString())
        assertEquals(through, faked[1])
        assertTrue(FakeIp.throughNode("proxy-dns-JP"))
        assertFalse(FakeIp.throughNode("local-dns"))
        assertFalse(FakeIp.throughNode("own-dns-1"))
        assertTrue(FakeIp.isRealNames(FakeIp.REAL, "proxy-dns-US"))
    }
}
