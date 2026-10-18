package io.nekohasekai.sfa.compose.theme

/** A decision made from a completed photo, never from a scrolling label. */
internal object BrightWallpaperPolicy {
    fun shouldCompose(luminances: List<Float>): Boolean {
        if (luminances.isEmpty()) return false
        val highlights = luminances.count { it >= 0.45f }.toFloat() / luminances.size
        return luminances.average() >= 0.22 && highlights >= 0.18f
    }

    fun portraitSpace(
        availableHeight: Float,
        topInset: Float,
        bottomInset: Float,
        serviceRunning: Boolean = false,
    ): Float {
        val usable = (availableHeight - topInset - bottomInset).coerceAtLeast(0f)
        // The caller has already removed the measured connection bar. Running
        // dashboards add traffic and connection cards, so give their scrollable
        // reading frame more height without shrinking the controls or type.
        val photoShare = if (serviceRunning) 0.36f else 0.50f
        val minimumReadingHeight = if (serviceRunning) 320f else 240f
        return (usable * photoShare)
            .coerceAtMost((usable - minimumReadingHeight).coerceAtLeast(0f))
    }
}
