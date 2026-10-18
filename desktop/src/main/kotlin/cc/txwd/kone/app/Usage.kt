package cc.txwd.kone.app

import java.io.File
import java.time.LocalDate
import java.util.Properties

/** Bytes down and up over a stretch of days. */
data class Used(val down: Long, val up: Long) {
    val total get() = down + up
}

/**
 * How much KOne has carried, day by day, kept in usage.properties in the App's folder for Tools →
 * Usage: today and this month. The core's traffic comes in each second; the file is written now
 * and then, and when KOne lets the connection go. Days before last month are let go.
 */
class Usage(private val file: File, private val date: () -> LocalDate = LocalDate::now) {
    private val days = HashMap<LocalDate, Used>()
    private var unsaved = 0L
    private var savedAt = 0L

    init {
        val values = Properties().apply { runCatching { file.inputStream().use(::load) } }
        for (name in values.stringPropertyNames()) {
            val day = runCatching { LocalDate.parse(name) }.getOrNull() ?: continue
            val parts = values.getProperty(name).split(',')
            val down = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
            val up = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: continue
            days[day] = Used(down, up)
        }
    }

    /** [down] and [up] bytes more today; written to the file once a minute at most. */
    @Synchronized
    fun add(down: Long, up: Long, now: Long = System.currentTimeMillis()) {
        if (down <= 0 && up <= 0) return
        val day = date()
        val was = days[day] ?: Used(0, 0)
        days[day] = Used(was.down + down.coerceAtLeast(0), was.up + up.coerceAtLeast(0))
        unsaved += down + up
        if (now - savedAt >= 60_000) save(now)
    }

    @Synchronized
    fun today(): Used = days[date()] ?: Used(0, 0)

    @Synchronized
    fun month(): Used {
        val day = date()
        return days.filterKeys { it.year == day.year && it.month == day.month }.values.fold(Used(0, 0)) { sum, used -> Used(sum.down + used.down, sum.up + used.up) }
    }

    /** Writes what is not yet in the file. */
    @Synchronized
    fun save(now: Long = System.currentTimeMillis()) {
        savedAt = now
        if (unsaved == 0L) return
        val keep = date().withDayOfMonth(1).minusMonths(1)
        days.keys.removeIf { it < keep }
        val values = Properties()
        days.forEach { (day, used) -> values.setProperty(day.toString(), "${used.down},${used.up}") }
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { values.store(it, null) }
            unsaved = 0
        }
    }
}
