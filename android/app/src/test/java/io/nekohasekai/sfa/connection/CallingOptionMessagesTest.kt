package io.nekohasekai.sfa.connection

import org.junit.Assert.*
import org.junit.Test

class CallingOptionMessagesTest {
    @Test fun onlyTheAcceptedCurrentConnectionCanDisplayApplied() {
        val update = CallingOptionUpdate(result = CallingApplyResult.Applied, confirmedEpoch = 17)
        assertEquals("Applied to the active connection", callingUpdateMessage(update, 7, 7, 17, true))
        for (message in listOf(
            callingUpdateMessage(update, 7, 7, 18, true),
            callingUpdateMessage(update, 7, 8, 17, true),
            callingUpdateMessage(update, 7, null, null, false),
            callingUpdateMessage(CallingOptionUpdate(result = CallingApplyResult.Applied), 7, 7, 17, true),
        )) {
            assertFalse(message.contains("Applied to the active connection"))
            assertTrue(message.contains("Unconfirmed", ignoreCase = true))
        }
    }

    @Test fun FailedRecoveryRemainsVisibleWithoutAConnectionAndNeverClaimsConnectionRestored() {
        for (recovery in CallingSettingsRecovery.entries) {
            val update = CallingOptionUpdate(result = CallingApplyResult.Failed(CallingApplyFailure.ReloadFailed, recovery))
            val message = callingUpdateMessage(update, 7, null, null, false)
            assertTrue(message.isNotEmpty())
            assertFalse(message.contains("Connection restored"))
            assertFalse(message.contains("Applied to the active connection"))
        }
    }
}
