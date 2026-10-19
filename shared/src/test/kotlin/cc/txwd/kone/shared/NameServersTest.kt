package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameServersTest {
    @Test
    fun anEntryIsTypedEitherWayRound() {
        assertEquals(NameServer("corp.example.org", "192.0.2.53"), NameServers.parse(" Corp.Example.org 192.0.2.53 "))
        assertEquals(NameServer("corp.example.org", "2001:db8::53"), NameServers.parse("2001:DB8::53 corp.example.org"))
        assertEquals(NameServer("corp.example.org", "192.0.2.53"), NameServers.parse("corp.example.org=192.0.2.53"))
        // A server is an address, never a name to look up first; a domain has two labels or more.
        assertNull(NameServers.parse("corp.example.org dns.example.org"))
        assertNull(NameServers.parse("corp 192.0.2.53"))
        assertNull(NameServers.parse("corp.example.org 192.0.2.0/24"))
    }

    @Test
    fun eachDomainOnceAndSixteenAtMost() {
        val groups = NameServers.clean(
            listOf(NameServer("corp.example.org", "192.0.2.53"), NameServer("corp.example.org", "198.51.100.53")) +
                (1..20).map { NameServer("d$it.example.org", "192.0.2.53") },
        )
        assertEquals(listOf("own-dns-1"), groups.map { it.tag })
        assertEquals(NameServers.MOST, groups.single().domains.size)
        assertEquals("corp.example.org", groups.single().domains.first())
    }
}
