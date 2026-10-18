package io.nekohasekai.sfa.compose.theme

internal class WallpaperRotation(private val intervalMs: Long = 10 * 60 * 1000L) {
    data class Change(val id: String, val wallTime: Long)
    private var lastElapsed: Long? = null

    fun check(ids: List<String>, current: String, savedWallTime: Long, nowWall: Long, nowElapsed: Long): Change? {
        require(ids.isNotEmpty())
        if (current !in ids || savedWallTime <= 0L) {
            lastElapsed = nowElapsed
            return Change(current.takeIf { it in ids } ?: ids.first(), nowWall)
        }
        val elapsed = lastElapsed
        val due = if (elapsed == null) nowWall - savedWallTime >= intervalMs else nowElapsed - elapsed >= intervalMs
        if (nowWall < savedWallTime || (elapsed != null && nowElapsed < elapsed)) {
            lastElapsed = nowElapsed
            return Change(current, nowWall)
        }
        if (due) {
            lastElapsed = nowElapsed
            return Change(ids[(ids.indexOf(current) + 1) % ids.size], nowWall)
        }
        if (elapsed == null) {
            lastElapsed = nowElapsed - (nowWall - savedWallTime).coerceIn(0L, intervalMs)
        }
        return null
    }
}
