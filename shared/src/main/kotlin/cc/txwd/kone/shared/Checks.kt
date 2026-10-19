package cc.txwd.kone.shared

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** How one check of a diagnosis went. */
enum class Outcome { Running, Pass, Warn, Fail }

/** One check of a diagnosis: what was checked, how it went, and a few words on it. */
data class Check(val title: String, val outcome: Outcome, val detail: String = "")

/**
 * Checks both Apps make the same way. A wrong clock is the one cause no node can fix and no
 * client names: VMess turns away a client more than 90 seconds off, and certificates fail when
 * the clock is far out, so a node simply "does not work".
 */
object Checks {
    const val CLOCK = "Clock"

    /** Seconds this computer's clock is ahead of a server's HTTP Date (behind when negative); null when it is not one. */
    fun skew(date: String, now: Long = System.currentTimeMillis()): Long? =
        runCatching { (now - ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()) / 1000 }.getOrNull()

    /** The clock against a server's: right under 30 s, a warning under 90 s, a failure beyond. Null when unknown. */
    fun clock(skew: Long?): Check? {
        skew ?: return null
        val off = kotlin.math.abs(skew)
        val words = when {
            off < 90 -> "$off s"
            off < 3_600 -> "${off / 60} min"
            off < 2 * 86_400 -> "${off / 3_600} h"
            else -> "${off / 86_400} days"
        } + if (skew > 0) " ahead" else " behind"
        return when {
            off < 30 -> Check(CLOCK, Outcome.Pass, "Right")
            off < 90 -> Check(CLOCK, Outcome.Warn, words)
            else -> Check(CLOCK, Outcome.Fail, words)
        }
    }
}
