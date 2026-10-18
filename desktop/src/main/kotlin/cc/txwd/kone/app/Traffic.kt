package cc.txwd.kone.app

import cc.txwd.kone.core.CoreConnection
import java.io.File
import java.time.LocalDate
import java.util.Properties

/** Whose traffic: the node it went through, or the program it came from. */
enum class TrafficBy(val key: String) { Node("node"), App("app") }

/**
 * How much each node and each program carried, day by day, for Tools → Usage. It is read from
 * the core's connection list, a few seconds apart: each connection counts what it carried since
 * it was last seen, so nothing is counted twice. A connection that closes between two looks
 * loses its last few seconds; the day's totals in [Usage] stay exact.
 *
 * Kept in traffic.properties in the App's folder, written once a minute at most; [KEEP_DAYS] days
 * are kept, and each day keeps its [MOST] largest names of each kind, the rest summed as Other.
 */
class Traffic(private val file: File, private val date: () -> LocalDate = LocalDate::now) {
    private val days = HashMap<LocalDate, HashMap<String, Used>>()
    private val seen = HashMap<String, Pair<Long, Long>>()
    private var unsaved = false
    private var savedAt = 0L

    init {
        val values = Properties().apply { runCatching { file.inputStream().use(::load) } }
        for (key in values.stringPropertyNames()) {
            // 2026-10-18|node|Japan = down,up
            val parts = key.split('|', limit = 3)
            if (parts.size != 3 || TrafficBy.entries.none { it.key == parts[1] } || parts[2].isEmpty()) continue
            val day = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: continue
            val numbers = values.getProperty(key).split(',')
            val down = numbers.getOrNull(0)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: continue
            val up = numbers.getOrNull(1)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: continue
            days.getOrPut(day, ::HashMap)["${parts[1]}|${parts[2]}"] = Used(down, up)
        }
    }

    /**
     * Counts what each of [connections] carried since it was last seen, under its node (named by
     * [nodeName]) and its program. Connections no longer listed are let go.
     */
    @Synchronized
    fun sample(connections: List<CoreConnection>, nodeName: (String) -> String, now: Long = System.currentTimeMillis()) {
        val today = days.getOrPut(date(), ::HashMap)
        val listed = HashSet<String>(connections.size)
        for (connection in connections) {
            listed += connection.id
            val (wasDown, wasUp) = seen[connection.id] ?: (0L to 0L)
            seen[connection.id] = connection.down to connection.up
            val down = (connection.down - wasDown).coerceAtLeast(0)
            val up = (connection.up - wasUp).coerceAtLeast(0)
            if (down == 0L && up == 0L) continue
            val node = if (connection.via.isEmpty() || connection.via == "direct") DIRECT else nodeName(connection.via)
            add(today, "${TrafficBy.Node.key}|$node", down, up)
            add(today, "${TrafficBy.App.key}|${connection.program ?: SYSTEM}", down, up)
            unsaved = true
        }
        seen.keys.retainAll(listed)
        if (now - savedAt >= 60_000) save(now)
    }

    /** Lets go of the connections seen so far, as when the core starts afresh. */
    @Synchronized
    fun forget() = seen.clear()

    /** The largest by down and up together over the last [span] days (1 is today), largest first. */
    @Synchronized
    fun top(by: TrafficBy, span: Int): List<Pair<String, Used>> {
        val from = date().minusDays(span - 1L)
        val sums = HashMap<String, Used>()
        days.filterKeys { it >= from }.values.forEach { day ->
            day.forEach { (key, used) ->
                if (!key.startsWith(by.key + "|")) return@forEach
                val name = key.substringAfter('|')
                val was = sums[name] ?: Used(0, 0)
                sums[name] = Used(was.down + used.down, was.up + used.up)
            }
        }
        return sums.entries.map { it.key to it.value }.sortedByDescending { it.second.total }
    }

    /** Each of the last [span] days, oldest first, with what all nodes carried that day. */
    @Synchronized
    fun daily(span: Int): List<Pair<LocalDate, Used>> {
        val today = date()
        return (span - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            day to (days[day].orEmpty().filterKeys { it.startsWith(TrafficBy.Node.key + "|") }.values
                .fold(Used(0, 0)) { sum, used -> Used(sum.down + used.down, sum.up + used.up) })
        }
    }

    /** Writes what is not yet in the file. */
    @Synchronized
    fun save(now: Long = System.currentTimeMillis()) {
        savedAt = now
        if (!unsaved) return
        val keep = date().minusDays(KEEP_DAYS - 1L)
        days.keys.removeIf { it < keep }
        days.values.forEach(::trim)
        val values = Properties()
        days.forEach { (day, entries) -> entries.forEach { (key, used) -> values.setProperty("$day|$key", "${used.down},${used.up}") } }
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { values.store(it, null) }
            unsaved = false
        }
    }

    private fun add(day: HashMap<String, Used>, key: String, down: Long, up: Long) {
        val was = day[key] ?: Used(0, 0)
        day[key] = Used(was.down + down, was.up + up)
    }

    /** Keeps a day's [MOST] largest of each kind, the rest summed as Other, so a day of many programs stays small. */
    private fun trim(day: HashMap<String, Used>) {
        for (by in TrafficBy.entries) {
            val other = "${by.key}|$OTHER"
            val ranked = day.entries.filter { it.key.startsWith(by.key + "|") && it.key != other }.sortedByDescending { it.value.total }
            if (ranked.size <= MOST) continue
            ranked.drop(MOST).forEach { (key, used) ->
                day.remove(key)
                add(day, other, used.down, used.up)
            }
        }
    }

    companion object {
        const val KEEP_DAYS = 31
        const val MOST = 40
        const val DIRECT = "Direct"
        const val OTHER = "Other"
        /** A connection the core could not tie to a program (Windows' own, or through the system proxy). */
        const val SYSTEM = "Unknown program"
    }
}
