package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionTermsTest {
    private val gb = 1024L * 1024 * 1024
    private val now = 1_800_000_000_000L

    @Test
    fun termsAreKeptPerProfileAndForgotten() {
        val terms = UserInfo(upload = 2 * gb, download = 10 * gb, total = 100 * gb, expire = now / 1000 + 30 * 86_400)
        var all = SubscriptionTerms.with("{}", 7, terms)
        all = SubscriptionTerms.with(all, 8, UserInfo(0, 1, 0, null))
        assertEquals(terms, SubscriptionTerms.read(all, 7))
        assertEquals(UserInfo(0, 1, 0, null), SubscriptionTerms.read(all, 8))
        assertNull(SubscriptionTerms.read(SubscriptionTerms.with(all, 7, null), 7))
        assertNull(SubscriptionTerms.read("not json", 7))
    }

    @Test
    fun theRowSaysWhatIsUsedOrWhatIsWrong() {
        val month = UserInfo(2 * gb, 10 * gb, 100 * gb, now / 1000 + 30 * 86_400)
        val (said, warning) = SubscriptionTerms.summary("Example Cloud", month, now)!!
        assertEquals(false, warning)
        assertEquals(true, said.startsWith("12.00 GB of 100.00 GB · until "))
        assertEquals("Example Cloud has used 95% of its data" to true, SubscriptionTerms.summary("Example Cloud", UserInfo(0, 95 * gb, 100 * gb, null), now))
        assertEquals("Example Cloud has ended" to true, SubscriptionTerms.summary("Example Cloud", UserInfo(0, 0, 0, now / 1000 - 1), now))
        // Nothing to say without a total or an end.
        assertNull(SubscriptionTerms.summary("Example Cloud", UserInfo(0, gb, 0, null), now))
    }

    @Test
    fun wordsAreKeptCleanAndForgottenWhenEmpty() {
        val all = SubscriptionTerms.withWords("{}", 7, " Trial|Russia ,  Trial ")
        assertEquals("Trial | Russia", SubscriptionTerms.words(all, 7))
        assertEquals("", SubscriptionTerms.words(SubscriptionTerms.withWords(all, 7, "  "), 7))
        assertEquals("", SubscriptionTerms.words("not json", 7))
    }
}
