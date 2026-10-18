package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitIpTest {
    @Test fun acceptsOnlyACompletePublicIpLiteral() {
        assertEquals("203.0.113.12", parseExitIp(" 203.0.113.12\n"))
        assertEquals("2001:db8::12", parseExitIp("2001:db8::12"))
    }

    @Test fun failuresAndHostnamesHaveNoDisplayValue() {
        for (value in listOf("", "Not tested", "example.com", "IP: 8.8.8.8", "{\"ip\":\"8.8.8.8\"}", "8.8.8.8\n1.1.1.1", "a".repeat(129))) {
            assertNull(value, parseExitIp(value))
        }
    }

    @Test fun traceGivesAddressAndCountry() {
        assertEquals(ExitInfo("203.0.113.12", "JP"), parseExitTrace("fl=1\nh=www.cloudflare.com\nip=203.0.113.12\nloc=jp\ncolo=NRT\n"))
        assertEquals(ExitInfo("203.0.113.12", null), parseExitTrace("ip=203.0.113.12\nloc=XX\n"))
        assertEquals(ExitInfo("203.0.113.12", null), parseExitTrace("ip=203.0.113.12\nloc=T1\n"))
        assertNull(parseExitTrace("loc=US\n"))
        assertNull(parseExitTrace("ip=10.0.0.1\nloc=US\n"))
        assertNull(parseExitTrace("<html>blocked</html>"))
    }

    @Test fun rejectsInvalidAmbiguousAndPrivateAddresses() {
        for (value in listOf("256.1.2.3", "1.2.3", "01.2.3.4", ":::1", "fe80::1%wlan0", "0.0.0.0", "127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1")) {
            assertNull(value, parseExitIp(value))
        }
    }
}
