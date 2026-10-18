package io.nekohasekai.sfa.compose.screen.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleAddressTest {
    // A made-up private entry; the private edition real one lives only in local.properties.
    private val home = ConsoleAddress.parse("https://console.example.ts.net")!!

    @Test
    fun onlyAnHttpsAddressWithAHostIsTaken() {
        assertEquals("console.example.ts.net", home.host)
        assertEquals("console.example.ts.net", ConsoleAddress.parse("  https://console.example.ts.net/  ")!!.host)
        listOf("", "http://console.example.ts.net", "https://", "https://user:pass@console.example.ts.net", "javascript:alert(1)", "not an address")
            .forEach { assertNull(it, ConsoleAddress.parse(it)) }
    }

    @Test
    fun theConsoleAndItsPagesAreInsideEverythingElseIsNot() {
        assertTrue(ConsoleAddress.sameOrigin("https://console.example.ts.net/#/customers/1", home))
        assertTrue(ConsoleAddress.sameOrigin("https://CONSOLE.example.ts.net:443/api/state", home))
        assertFalse(ConsoleAddress.sameOrigin("http://console.example.ts.net/", home))
        assertFalse(ConsoleAddress.sameOrigin("https://console.example.ts.net:8443/", home))
        assertFalse(ConsoleAddress.sameOrigin("https://example.com/", home))
        assertFalse(ConsoleAddress.sameOrigin("https://console.example.ts.net.evil.example/", home))
        assertFalse(ConsoleAddress.sameOrigin("kone://claim?code=x", home))
    }
}
