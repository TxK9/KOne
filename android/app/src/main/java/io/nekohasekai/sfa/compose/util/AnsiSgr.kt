package io.nekohasekai.sfa.compose.util

/** Text attributes selected by ANSI SGR escapes. [foreground] is ARGB; null means the theme's default. */
internal data class AnsiTextStyle(
    val foreground: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = AnsiTextStyle()
    }
}

/** A styled range of [AnsiText.text], end exclusive. */
internal data class AnsiRun(val start: Int, val end: Int, val style: AnsiTextStyle)

internal data class AnsiText(val text: String, val runs: List<AnsiRun>)

/**
 * Parses the SGR subset written by the core log formatter and common tools.
 *
 * Attributes accumulate across escapes until reset, as in a terminal. The core
 * writes connection IDs as `ESC[38;5;Nm` with N in the xterm 6x6x6 cube, so
 * extended colors are decoded as a unit instead of as separate codes.
 */
internal object AnsiSgr {
    val escape = Regex("\u001B\\[[;\\d]*m")

    private const val OPAQUE = 0xFF shl 24

    // Indexes 0-7 keep the log palette tuned for the log screen; 8-15 are their bright variants.
    private val basePalette = intArrayOf(
        0xFF000000.toInt(),
        0xFFFF2158.toInt(),
        0xFF2ECC71.toInt(),
        0xFFE5E500.toInt(),
        0xFF3498DB.toInt(),
        0xFF9B59B6.toInt(),
        0xFF5DADE2.toInt(),
        0xFFECF0F1.toInt(),
    )
    private val cubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

    fun strip(text: String): String = text.replace(escape, "")

    fun parse(text: String): AnsiText {
        val builder = StringBuilder(text.length)
        val runs = ArrayList<AnsiRun>()
        var style = AnsiTextStyle.PLAIN
        var runStart = 0
        var position = 0

        fun closeRun() {
            if (!style.isPlain && runStart < builder.length) runs += AnsiRun(runStart, builder.length, style)
            runStart = builder.length
        }

        for (match in escape.findAll(text)) {
            builder.append(text, position, match.range.first)
            position = match.range.last + 1
            val next = apply(style, match.value.substring(2, match.value.length - 1))
            if (next != style) {
                closeRun()
                style = next
            }
        }
        builder.append(text, position, text.length)
        closeRun()
        return AnsiText(builder.toString(), runs)
    }

    fun color256(index: Int): Int? = when (index) {
        in 0..7 -> basePalette[index]
        in 8..15 -> basePalette[index - 8]
        in 16..231 -> {
            val cube = index - 16
            rgb(cubeLevels[cube / 36], cubeLevels[cube / 6 % 6], cubeLevels[cube % 6])
        }
        in 232..255 -> (8 + (index - 232) * 10).let { rgb(it, it, it) }
        else -> null
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int = OPAQUE or (red shl 16) or (green shl 8) or blue

    private fun apply(current: AnsiTextStyle, parameters: String): AnsiTextStyle {
        // An empty parameter list, or an empty field, means 0 (reset).
        val codes = parameters.split(';').map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }
        var style = current
        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> style = AnsiTextStyle.PLAIN
                1 -> style = style.copy(bold = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                22 -> style = style.copy(bold = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                in 30..37 -> style = style.copy(foreground = basePalette[code - 30])
                39 -> style = style.copy(foreground = null)
                in 90..97 -> style = style.copy(foreground = basePalette[code - 90])
                38, 48 -> {
                    val (color, consumed) = extendedColor(codes, index + 1) ?: return style
                    if (code == 38) style = style.copy(foreground = color)
                    index += consumed
                }
            }
            index++
        }
        return style
    }

    /** Returns the color and the number of codes after 38/48 that it used, or null if malformed. */
    private fun extendedColor(codes: List<Int>, start: Int): Pair<Int, Int>? = when (codes.getOrNull(start)) {
        5 -> codes.getOrNull(start + 1)?.let(::color256)?.let { it to 2 }
        2 -> {
            val channels = (1..3).map { codes.getOrNull(start + it) ?: -1 }
            if (channels.all { it in 0..255 }) rgb(channels[0], channels[1], channels[2]) to 4 else null
        }
        else -> null
    }
}
