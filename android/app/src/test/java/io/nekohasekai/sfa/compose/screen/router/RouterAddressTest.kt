package io.nekohasekai.sfa.compose.screen.router

import org.junit.Assert.*
import org.junit.Test

class RouterAddressTest {
    @Test fun routerAuthenticationStaysOnOneHttpsOrigin() {
        val home=RouterAddress.parse("https://router.example:8443/")!!
        assertEquals("/cgi-bin/luci/admin/services/passwall2", home.path)
        assertTrue(RouterAddress.sameOrigin("https://router.example:8443/luci-static/a.js",home))
        for(url in listOf("http://router.example:8443/", "https://router.example/", "https://router.example.attacker.invalid:8443/", "https://user@router.example:8443/", "file:///etc/passwd")) {
            assertFalse(url,RouterAddress.sameOrigin(url,home))
        }
    }
    @Test fun credentialsAndSessionLinksAreNeverSavedAsAddresses() {
        for(url in listOf("http://router.example/", "https://user:password@router.example/", "https://router.example/?token=secret", "https://router.example/#secret", "javascript:alert(1)", "https://router.example:0/")) {
            assertNull(url,RouterAddress.parse(url))
        }
        assertNotNull(RouterAddress.parse("https://router.example/cgi-bin/luci/admin/services/passwall2"))
    }
    @Test fun fingerprintsMustBeExactSha256Values() {
        assertEquals("ab".repeat(32),RouterAddress.pin(List(32){"AB"}.joinToString(":")))
        assertNull(RouterAddress.pin(""));assertNull(RouterAddress.pin("ab".repeat(31)));assertNull(RouterAddress.pin("gg".repeat(32)))
    }
}
