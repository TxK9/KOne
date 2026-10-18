package cc.txwd.kone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostsTest {
    @Test
    fun anEntryIsTypedEitherWayRoundAndWrittenOneWay() {
        assertEquals(Host("nas.example.org", "192.0.2.10"), Hosts.parse(" NAS.Example.org   192.0.2.10 "))
        // A line from a hosts file: the address first.
        assertEquals(Host("nas.example.org", "2001:db8::10"), Hosts.parse("2001:DB8:0:0:0:0:0:10\tnas.example.org"))
        for (wrong in listOf("", "nas.example.org", "nas.example.org 192.0.2.0/24", "single 192.0.2.1", "a b c", "nas.example.org localhost", "192.0.2.1 192.0.2.2")) {
            assertNull(Hosts.parse(wrong), wrong)
        }
    }

    @Test
    fun answersAreGroupedByNameAndBounded() {
        val groups = Hosts.clean(listOf(Host("nas.example.org", "192.0.2.10"), Host("printer.example.org", "192.0.2.20"), Host("nas.example.org", "2001:db8::10"), Host("nas.example.org", "192.0.2.10")))
        assertEquals(listOf(Hosts.Group("nas.example.org", listOf("192.0.2.10"), listOf("2001:db8::10")), Hosts.Group("printer.example.org", listOf("192.0.2.20"), emptyList())), groups)
        assertEquals(Hosts.NAMES, Hosts.clean((1..80).map { Host("h$it.example.org", "192.0.2.1") }).size)
        assertEquals(Hosts.ADDRESSES, Hosts.clean((1..20).map { Host("h.example.org", "192.0.2.$it") }).single().v4.size)
        // Kept as entries again, in the same order by name.
        assertEquals(listOf(Host("nas.example.org", "192.0.2.10"), Host("nas.example.org", "2001:db8::10"), Host("printer.example.org", "192.0.2.20")), Hosts.flatten(groups))
    }
}
