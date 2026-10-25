package io.nekohasekai.sfa.compose.screen.account

import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.managed.ManagedStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** The binding's state as weather: working, degraded but still connecting, failing, or paused. */
internal fun accountWeather(status: ManagedStatus): NodeWeather = when (status) {
    ManagedStatus.Synced -> NodeWeather.Clear
    // The last configuration keeps working while the service is out of reach or has no route yet.
    ManagedStatus.Waiting, ManagedStatus.Offline -> NodeWeather.Mist
    ManagedStatus.Failed -> NodeWeather.Cut
    ManagedStatus.Refused, ManagedStatus.Revoked, ManagedStatus.Unbound -> NodeWeather.Rest
}

internal enum class TermState { Active, EndingSoon, Ended }

/** When the service's terms end, as a date on this phone, and how near that is. */
internal data class AccountExpiry(val date: LocalDate, val state: TermState)

/** Ending within this long is said as ending soon, as subscriptions say it. */
internal val ENDING_SOON: Duration = Duration.ofDays(3)

/**
 * Reads the console's `expiresAt`: an instant (with or without an offset) or a bare date, which
 * lasts through that day. Anything else is not shown rather than guessed at.
 */
internal fun accountExpiry(expiresAt: String?, now: Instant, zone: ZoneId): AccountExpiry? {
    val text = expiresAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).atZone(zone).toInstant() }.getOrNull()
    val date: LocalDate
    val end: Instant
    if (instant != null) {
        date = instant.atZone(zone).toLocalDate()
        end = instant
    } else {
        date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
        end = date.plusDays(1).atStartOfDay(zone).toInstant()
    }
    val state = when {
        !end.isAfter(now) -> TermState.Ended
        Duration.between(now, end) <= ENDING_SOON -> TermState.EndingSoon
        else -> TermState.Active
    }
    return AccountExpiry(date, state)
}

internal fun TermState.weather(): NodeWeather? = when (this) {
    TermState.Active -> null
    TermState.EndingSoon -> NodeWeather.Mist
    TermState.Ended -> NodeWeather.Cut
}
