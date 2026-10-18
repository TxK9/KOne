package io.nekohasekai.sfa.compose.theme

/** A fixed clear film: photo detail stays sharp and foregrounds stay stable. */
internal object LightFilmMaterial {
    const val START_ALPHA = 0.70f
    private const val REQUESTED_END_ALPHA = 0.58f
    private const val TARGET = 5f

    val endAlpha: Float = maxOf(
        REQUESTED_END_ALPHA,
        listOf(KninePalette.DARK_INK, KninePalette.DARK_ACCENT, KninePalette.DARK_ERROR).maxOf {
            KninePalette.minimumSupportAlpha(it, 0xFF000000.toInt(), KninePalette.LIGHT_READING_SURFACE, TARGET)
        },
    )

    val minimumBackground: Int get() = KninePalette.composite(
        KninePalette.LIGHT_READING_SURFACE, 0xFF000000.toInt(), endAlpha,
    )
}
