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
        assertEquals(4, faked.size)
        assertEquals(rules[0], faked[0])
        assertEquals("""{"clash_mode":"Global","server":"fake-dns","query_type":["A"]}""", faked[1].toString())
        assertEquals(rules[1], faked[2])
        assertEquals("""{"query_type":["A"],"server":"fake-dns"}""", faked[3].toString())
    }
}
