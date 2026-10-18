package cc.txwd.kone.app

import cc.txwd.kone.core.DnsReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsLookupTest {
    @Test
    fun takesANameHoweverItIsPasted() {
        assertEquals("www.example.com", DnsLookup.clean(" https://WWW.Example.com:8443/path?q=1 "))
        assertEquals("example.com", DnsLookup.clean("example.com."))
        assertEquals("example.com", DnsLookup.clean("user@example.com"))
        assertNull(DnsLookup.clean("not a name"))
        assertNull(DnsLookup.clean(""))
        assertNull(DnsLookup.clean("203.0.113.1"))
    }

    @Test
    fun knowsTheNamesTheConsoleRefuses() {
        val source = Json.parseToJsonElement(
            """{"route": {"rules": [{"domain_suffix": ["claude.example", ".sub.example.org"], "domain": ["one.example.net"], "action": "reject"},
               {"domain_suffix": ["wide.example"], "network": "tcp", "action": "reject"}]}}""",
        ) as JsonObject
        assertTrue(DnsLookup.refused("claude.example", source))
        assertTrue(DnsLookup.refused("api.claude.example", source))
        assertFalse(DnsLookup.refused("notclaude.example", source))
        assertTrue(DnsLookup.refused("a.sub.example.org", source))
        assertFalse(DnsLookup.refused("sub.example.org", source))
        assertTrue(DnsLookup.refused("one.example.net", source))
        assertFalse(DnsLookup.refused("two.one.example.net", source))
        // Not a refusal KOne keeps, so not one it reports.
        assertFalse(DnsLookup.refused("wide.example", source))
        assertFalse(DnsLookup.refused("claude.example", null))
    }

    @Test
    fun describesEachReply() {
        assertEquals(Check("KOne", Outcome.Fail, DnsLookup.REFUSED), DnsLookup.describe("KOne", DnsReply(5, emptyList()), 3))
        assertEquals(Check("Direct", Outcome.Fail, DnsLookup.NO_NAME), DnsLookup.describe("Direct", DnsReply(3, emptyList()), 3))
        assertEquals(Check("Remote", Outcome.Warn, "No address · 40 ms"), DnsLookup.describe("Remote", DnsReply(0, listOf("CNAME a.example")), 40))
        val many = DnsReply(0, listOf("CNAME a.example") + (1..5).map { "A 203.0.113.$it" })
        assertEquals(Check("Apps", Outcome.Pass, "203.0.113.1, 203.0.113.2, 203.0.113.3 +2 · 12 ms"), DnsLookup.describe("Apps", many, 12))
    }

    @Test
    fun addsTheChecksUpToOneSentence() {
        val fine = listOf(Check("Apps", Outcome.Pass, "203.0.113.1 · 5 ms"), Check("Remote", Outcome.Pass, "203.0.113.1, 203.0.113.2 · 40 ms"))
        assertEquals("The name resolves normally", DnsLookup.verdict(fine, refused = false, ipv6 = true))
        assertTrue(DnsLookup.verdict(emptyList(), refused = true, ipv6 = true).startsWith("Your provider blocks"))
        val apart = listOf(Check("Apps", Outcome.Pass, "203.0.113.1 · 5 ms"), Check("Remote", Outcome.Pass, "198.51.100.1 +1 · 40 ms"))
        assertTrue(DnsLookup.verdict(apart, refused = false, ipv6 = true).startsWith("Servers give different addresses"))
        val ruled = listOf(Check("Apps", Outcome.Fail, DnsLookup.NO_NAME), Check("KOne", Outcome.Fail, DnsLookup.REFUSED))
        assertEquals("A rule in KOne refuses this name", DnsLookup.verdict(ruled, refused = false, ipv6 = true))
        val missing = listOf(Check("Apps", Outcome.Fail, DnsLookup.NO_NAME), Check("Remote", Outcome.Fail, DnsLookup.NO_NAME))
        assertTrue(DnsLookup.verdict(missing, refused = false, ipv6 = true).startsWith("No such name"))
        val silent = listOf(Check("Apps", Outcome.Fail, DnsLookup.NO_NAME), Check("Remote", Outcome.Fail, "No answer"))
        assertTrue(DnsLookup.verdict(silent, refused = false, ipv6 = true).startsWith("Nothing answers"))
        val half = listOf(Check("Apps", Outcome.Pass, "203.0.113.1 · 5 ms"), Check("Remote", Outcome.Fail, "No answer"))
        assertTrue(DnsLookup.verdict(half, refused = false, ipv6 = true).startsWith("Some servers do not answer"))
        val six = listOf(Check("Apps", Outcome.Pass, "203.0.113.1, 2001:db8::1 · 5 ms"))
        assertTrue(DnsLookup.verdict(six, refused = false, ipv6 = false).startsWith("IPv6 is off"))
    }

    @Test
    fun findsTheServersSettingsChose() {
        assertEquals("https://1.1.1.1/dns-query", DnsLookup.remoteUrl(""))
        assertEquals("https://9.9.9.9/dns-query", DnsLookup.remoteUrl("quad9"))
        assertEquals("https://dns.example.com/q", DnsLookup.remoteUrl("https://dns.example.com/q"))
        assertEquals("223.5.5.5", DnsLookup.directServer(""))
        assertEquals("119.29.29.29", DnsLookup.directServer("dnspod"))
        assertEquals("203.0.113.53", DnsLookup.directServer("203.0.113.53"))
    }
}
