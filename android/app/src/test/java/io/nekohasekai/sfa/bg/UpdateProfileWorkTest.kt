package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.selfuse.SubscriptionException
import org.json.JSONException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateProfileWorkTest {
    @Test
    fun aRefusedLinkWaitsForItsNextRefresh() {
        assertTrue(worthRetrying(SubscriptionException("busy", temporary = true)))
        assertTrue(worthRetrying(IOException("disk")))
        assertFalse(worthRetrying(SubscriptionException("The provider no longer has this link (404)")))
        // A body sing-box refuses will be refused again in fifteen minutes.
        assertFalse(worthRetrying(JSONException("bad")))
        assertFalse(worthRetrying(IllegalStateException("check failed")))
    }
}
