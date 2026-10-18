package io.nekohasekai.sfa.managed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedEndpointTest {
    @Test
    fun addressesAreHttpsOrThisPhoneItself() {
        assertEquals("https://kone.example.org", ManagedEndpoint.parse(" kone.example.org/ ")!!.base)
        assertEquals("https://kone.example.org:8443/console", ManagedEndpoint.parse("https://kone.example.org:8443/console/")!!.base)
        assertEquals("http://127.0.0.1:8768", ManagedEndpoint.parse("http://127.0.0.1:8768")!!.base)
        assertEquals("http://localhost:8790", ManagedEndpoint.parse("http://localhost:8790")!!.base)
        listOf(
            "http://kone.example.org",
            "http://192.168.1.5:8768",
            "ftp://kone.example.org",
            "https://user:pw@kone.example.org",
            "https://kone.example.org/?token=x",
            "https://kone.example.org/#x",
            "kone example.org",
            "",
        ).forEach { assertNull(it, ManagedEndpoint.parse(it)) }
    }

    @Test
    fun claimLinksCarryAddressAndCode() {
        val code = "AbCdEf0123456789_-AbCdEf0123456789_-AbCdEf0"
        val link = ManagedClaimLink.find("\u590d\u5236\u81ea\u7ba1\u7406\u540e\u53f0\nkone://claim?server=https%3A%2F%2Fkone.example.org&code=$code\n")!!
        assertEquals("https://kone.example.org", link.endpoint.base)
        assertEquals(code, link.code)
        assertNull(ManagedClaimLink.parse("kone://claim?server=http%3A%2F%2Fkone.example.org&code=$code"))
        assertNull(ManagedClaimLink.parse("kone://claim?server=https%3A%2F%2Fkone.example.org&code=\u77ed"))
        assertNull(ManagedClaimLink.parse("kone://claim?code=$code"))
        assertTrue(ManagedClaimLink.isInviteCode(code))
        assertTrue(!ManagedClaimLink.isInviteCode("\u9886\u53d6\u7801\u9886\u53d6\u7801\u9886\u53d6\u7801"))
    }
}
