package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressNameTest {
    @Test fun addressesAreRecognised() {
        for (name in listOf("203.0.113.10", "203.0.113.10:443", "2001:db8::1", "[2001:db8::1]:443", "node.example.com", "hk1.example.net:8443")) {
            assertTrue(name, looksLikeAddress(name))
        }
    }

    @Test fun ordinaryNamesAreNot() {
        for (name in listOf("美国", "Verizon", "美国Verizon｜新VPS中转", "Node 5", "v1.2", "Tokyo 2", "")) {
            assertFalse(name, looksLikeAddress(name))
        }
    }
}
