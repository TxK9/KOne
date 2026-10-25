package io.nekohasekai.sfa.compose.base

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicErrorMessageTest {
    @Test fun unknownCoreAndImportErrorsNeverReachTheInterface() {
        val fallback = "Action incomplete. Check your input or try again later."
        for (message in listOf(null, "", "dial tcp 203.0.113.6:443: failed",
            "outbound secret-tag: password=private-value", "vless://private-value@example.test",
            "{\"server\":\"example.test\",\"uuid\":\"private-value\"}", "\u79c1\u5bc6\u8282\u70b9\u53e3\u4ee4", "\u670d\u52a1\u6682\u505c private-value")) {
            assertEquals(fallback, publicErrorMessage(message))
        }
    }

    @Test fun knownActionableMessagesRemainVisibleOnlyOnExactMatch() {
        assertEquals("Settings could not be applied. Try again.", publicErrorMessage("Settings could not be applied. Try again."))
        assertEquals("The claim code is invalid or expired.", publicErrorMessage("The claim code is invalid or expired."))
    }
}
