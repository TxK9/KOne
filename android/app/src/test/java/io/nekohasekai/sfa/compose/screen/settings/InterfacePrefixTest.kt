package io.nekohasekai.sfa.compose.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfacePrefixTest {
    @Test
    fun onlyLettersDigitsAndUnderscoresAreKept() {
        assertEquals("wlan", normalizeInterfacePrefix("wlan"))
        assertEquals("rmnet_data", normalizeInterfacePrefix("  rmnet_data  "))
        assertEquals("eth0", normalizeInterfacePrefix("eth-0"))
        assertEquals("tun", normalizeInterfacePrefix("t u.n/"))
    }

    @Test
    fun nothingLeftFallsBackToEn() {
        assertEquals("en", normalizeInterfacePrefix(""))
        assertEquals("en", normalizeInterfacePrefix("   "))
        assertEquals("en", normalizeInterfacePrefix("-./"))
    }
}
