package cc.txwd.kone.app

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpiryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-24T04:00:00Z") // 12:00 on the 24th in Shanghai

    @Test
    fun saidOnlyInTheLastWeek() {
        assertNull(Expiry.hint(null, now, zone))
        assertNull(Expiry.hint("not a date", now, zone))
        assertNull(Expiry.hint("2026-12-02T00:00:00Z", now, zone), "eight days ahead says nothing yet")
        assertEquals("7 days remaining", Expiry.hint("2026-12-01T00:00:00Z", now, zone))
        assertEquals("Expires tomorrow", Expiry.hint("2026-11-25T08:00:00+08:00", now, zone))
        assertEquals("Expires today", Expiry.hint("2026-11-24T23:00:00+08:00", now, zone))
        assertEquals("Expired", Expiry.hint("2026-11-24T03:59:00Z", now, zone))
    }
}
