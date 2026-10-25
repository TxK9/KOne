package io.nekohasekai.sfa.update

import org.junit.Assert.*
import org.junit.Test

class KoneUpdateNoticePolicyTest {
    private val first = KoneUpdateNoticePolicy.releaseKey(10036, "a".repeat(64))
    private val second = KoneUpdateNoticePolicy.releaseKey(10037, "b".repeat(64))

    @Test fun aReleasePromptsOnceAndDismissalSurvivesAnotherLaunch() {
        assertTrue(KoneUpdateNoticePolicy.shouldPrompt(first, true, emptySet(), emptySet()))
        assertFalse(KoneUpdateNoticePolicy.shouldPrompt(first, true, setOf(first), emptySet()))
        assertFalse(KoneUpdateNoticePolicy.shouldPrompt(first, true, emptySet(), setOf(first)))
        assertTrue(KoneUpdateNoticePolicy.shouldPrompt(second, true, setOf(first), setOf(first)))
        assertFalse(KoneUpdateNoticePolicy.shouldPrompt(second, false, emptySet(), emptySet()))
    }

    @Test fun buildAndDigestTogetherIdentifyTheRelease() {
        assertEquals(first, KoneUpdateNoticePolicy.releaseKey(10036, "A".repeat(64)))
        assertNotEquals(first, KoneUpdateNoticePolicy.releaseKey(10036, "b".repeat(64)))
        assertNotEquals(first, KoneUpdateNoticePolicy.releaseKey(10037, "a".repeat(64)))
    }

    @Test fun permissionDenialDoesNotConsumeTheLaterNotificationOffer() {
        val notice = KoneUpdateNoticePolicy.notificationKey(first, null)
        assertFalse(KoneUpdateNoticePolicy.shouldNotify(first, notice, true, false, false, emptySet(), emptySet()))
        assertTrue(KoneUpdateNoticePolicy.shouldNotify(first, notice, true, false, true, emptySet(), emptySet()))
        assertFalse(KoneUpdateNoticePolicy.shouldNotify(first, notice, true, false, true, setOf(notice), emptySet()))
        assertFalse(KoneUpdateNoticePolicy.shouldNotify(first, notice, true, true, true, emptySet(), emptySet()))
        assertFalse(KoneUpdateNoticePolicy.shouldNotify(first, notice, false, false, true, emptySet(), emptySet()))
    }

    @Test fun laterSuppressesReadyNotificationsButANewReleaseCanNotify() {
        assertFalse(KoneUpdateNoticePolicy.shouldNotify(first, KoneUpdateNoticePolicy.notificationKey(first, null),
            true, false, true, emptySet(), setOf(first)))
        assertTrue(KoneUpdateNoticePolicy.shouldNotify(second, KoneUpdateNoticePolicy.notificationKey(second, null),
            true, false, true, emptySet(), setOf(first)))
    }

    @Test fun aManuallyStartedInstallCanHaveOneSeparateConfirmationNoticePerSession() {
        val ready = KoneUpdateNoticePolicy.notificationKey(first, null)
        val confirmation = KoneUpdateNoticePolicy.notificationKey(first, 41)
        assertNotEquals(ready, confirmation)
        assertTrue(KoneUpdateNoticePolicy.shouldNotify(first, confirmation, true, false, true, setOf(ready), emptySet()))
        assertFalse(KoneUpdateNoticePolicy.shouldNotify(first, confirmation, true, false, true, setOf(confirmation), emptySet()))
        assertNotEquals(confirmation, KoneUpdateNoticePolicy.notificationKey(first, 42))
    }
}
