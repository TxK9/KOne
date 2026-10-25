package io.nekohasekai.sfa.compose.screen.account

import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.managed.ManagedStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class AccountFactsTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    @Test
    fun everyStatusHasAWeatherAndOnlySyncedIsClear() {
        val clear = ManagedStatus.entries.filter { accountWeather(it) == NodeWeather.Clear }
        assertEquals(listOf(ManagedStatus.Synced), clear)
        assertEquals(NodeWeather.Mist, accountWeather(ManagedStatus.Offline))
        assertEquals(NodeWeather.Mist, accountWeather(ManagedStatus.Waiting))
        assertEquals(NodeWeather.Cut, accountWeather(ManagedStatus.Failed))
        assertEquals(NodeWeather.Rest, accountWeather(ManagedStatus.Refused))
    }

    @Test
    fun missingOrUnreadableEndDatesAreNotShown() {
        assertNull(accountExpiry(null, now, zone))
        assertNull(accountExpiry("  ", now, zone))
        assertNull(accountExpiry("next month", now, zone))
        assertNull(accountExpiry("2026-13-40", now, zone))
    }

    @Test
    fun instantsAreReadWithOrWithoutAnOffset() {
        assertEquals(AccountExpiry(LocalDate.of(2026, 12, 1), TermState.Active), accountExpiry("2026-12-01T08:00:00Z", now, zone))
        assertEquals(AccountExpiry(LocalDate.of(2026, 12, 1), TermState.Active), accountExpiry("2026-12-01T08:00:00+02:00", now, zone))
        assertEquals(AccountExpiry(LocalDate.of(2026, 12, 1), TermState.Active), accountExpiry("2026-12-01T08:00:00", now, zone))
    }

    @Test
    fun aBareDateLastsThroughThatDay() {
        assertEquals(TermState.EndingSoon, accountExpiry("2026-10-25", now, zone)?.state)
        assertEquals(TermState.Ended, accountExpiry("2026-10-24", now, zone)?.state)
        assertEquals(LocalDate.of(2026, 10, 25), accountExpiry("2026-10-25", now, zone)?.date)
    }

    @Test
    fun endingSoonIsWithinThreeDaysAndEndedIsPast() {
        assertEquals(TermState.EndingSoon, accountExpiry("2026-10-28T12:00:00Z", now, zone)?.state)
        assertEquals(TermState.Active, accountExpiry("2026-10-28T12:00:01Z", now, zone)?.state)
        assertEquals(TermState.Ended, accountExpiry("2026-10-25T12:00:00Z", now, zone)?.state)
        assertEquals(NodeWeather.Mist, TermState.EndingSoon.weather())
        assertEquals(NodeWeather.Cut, TermState.Ended.weather())
        assertNull(TermState.Active.weather())
    }

    @Test
    fun theDateShownIsThePhonesOwn() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(LocalDate.of(2026, 12, 2), accountExpiry("2026-12-01T20:00:00Z", now, tokyo)?.date)
    }

    @Test
    fun anEndWithinTheHourIsEndedOnTheMinuteAfterIt() {
        val end = "2026-10-25T12:40:00Z"
        assertEquals(TermState.EndingSoon, accountExpiry(end, Instant.parse("2026-10-25T12:39:00Z"), zone)?.state)
        assertEquals(TermState.Ended, accountExpiry(end, Instant.parse("2026-10-25T12:40:00Z"), zone)?.state)
    }

    @Test
    fun theEndDateIsWrittenInTheGivenLocale() {
        val date = LocalDate.of(2026, 10, 28)
        assertEquals("Oct 28, 2026", expiryDateText(date, Locale.US))
        assertEquals("28.10.2026", expiryDateText(date, Locale.GERMANY))
        assertNotEquals(expiryDateText(date, Locale.US), expiryDateText(date, Locale.JAPAN))
    }

    @Test
    fun theClockReadsTheZoneWithTheTime() {
        val before = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(ZoneId.of("Asia/Tokyo"), AccountClock.read().zone)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals(ZoneId.of("America/New_York"), AccountClock.read().zone)
        } finally {
            java.util.TimeZone.setDefault(before)
        }
    }
}
