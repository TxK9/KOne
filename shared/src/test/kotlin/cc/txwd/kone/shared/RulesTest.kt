package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
    @Test
    fun aRuleCanGoThroughAChosenCountry() {
        assertTrue(Rules.isAction("proxy"))
        assertTrue(Rules.isAction("via:JP"))
        assertFalse(Rules.isAction("via:jp"))
        assertFalse(Rules.isAction("via:JPN"))
        assertFalse(Rules.isAction("via:"))
        assertFalse(Rules.isAction("node"))
        assertEquals("JP", Rules.via("via:JP"))
        assertNull(Rules.via("proxy"))
        assertEquals("via:US", Rules.through("us"))
        assertEquals("proxy-dns-US", Rules.dnsThrough("US"))
    }

    @Test
    fun rulesThroughCountriesAreKeptAndListedOnce() {
        val rules = Rules.clean(
            listOf(
                Rule("suffix", "Example.com", "via:JP"), Rule("set", "geosite-netflix", "via:US"), Rule("suffix", "example.org", "via:JP"),
                Rule("suffix", "example.net", "via:Japan"), Rule("suffix", "example.com", "via:JP"),
            ),
            Rules.WINDOWS,
        )
        // Written one way, each once; an action that is not one is dropped with its rule.
        assertEquals(listOf(Rule("suffix", "example.com", "via:JP"), Rule("set", "geosite-netflix", "via:US"), Rule("suffix", "example.org", "via:JP")), rules)
        assertEquals(listOf("JP", "US"), Rules.countries(rules))
    }

    @Test
    fun aNameSaysOneCountryOrNone() {
        assertEquals("JP", Countries.of("Tokyo 01")?.code)
        assertEquals("US", Countries.of("🇺🇸 Los Angeles")?.code)
        assertNull(Countries.of("Relay 3"))
        // Two countries named: neither.
        assertNull(Countries.of("Japan to Singapore"))
    }
}
