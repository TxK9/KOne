package io.nekohasekai.sfa.compose.screen.account

import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.managed.ManagedStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The account page's clock: the time and the phone's zone, read together on each tick, so a zone
 * change reaches the end date as soon as the time does.
 */
internal data class AccountClock(val now: Long, val zone: ZoneId) {
    companion object {
        fun read(): AccountClock = AccountClock(System.currentTimeMillis(), ZoneId.systemDefault())
    }
}

/** The end date as written in [locale], the App's own language rather than the phone's. */
internal fun expiryDateText(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

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

/** JSON numbers may reach optString in decimal or exponent notation, including fractional seconds. */
private fun epochExpiry(text: String): Instant? = runCatching {
    if (text.length > 64) return null
    val value = text.toBigDecimalOrNull() ?: return null
    // Bound scale before decimal arithmetic, so hostile exponents cannot allocate huge powers.
    if (value.scale() !in -18..18 || value.signum() <= 0 || value > BigDecimal.valueOf(Instant.MAX.epochSecond)) return null
    val seconds = value.setScale(0, RoundingMode.DOWN).longValueExact()
    val nanos = value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).setScale(0, RoundingMode.DOWN).longValueExact()
    Instant.ofEpochSecond(seconds, nanos)
}.getOrNull()

/** Ending within this long is said as ending soon, as subscriptions say it. */
internal val ENDING_SOON: Duration = Duration.ofDays(3)

/**
 * Reads the console's Unix-seconds `expiresAt`, also accepting older ISO instants and bare
 * dates. A bare date lasts through that day. Invalid values are not shown.
 */
internal fun accountExpiry(expiresAt: String?, now: Instant, zone: ZoneId): AccountExpiry? {
    val text = expiresAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = epochExpiry(text) ?: runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).atZone(zone).toInstant() }.getOrNull()
    val date: LocalDate
    val end: Instant
    if (instant != null) {
        date = runCatching { instant.atZone(zone).toLocalDate() }.getOrNull() ?: return null
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
