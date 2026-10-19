package io.nekohasekai.sfa.compose.util

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.txwd.kone.shared.Countries
import cc.txwd.kone.shared.flagEmoji
import io.nekohasekai.sfa.compose.theme.BackdropIcon

/** Country flags stored as profile icons, e.g. "flag:JP". They render as emoji, not vectors. */
object FlagIcons {
    const val PREFIX = "flag:"

    fun idFor(code: String): String = PREFIX + code.uppercase()

    fun codeOf(iconId: String?): String? =
        iconId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { Countries.byCode(it) != null }

    /** The flag a node shows: the one chosen for it, else, with no icon chosen, the one its name names. */
    fun codeFor(iconId: String?, name: String): String? =
        codeOf(iconId) ?: if (iconId.isNullOrBlank()) Countries.all.filter { it.matches(name) }.singleOrNull()?.code else null

    fun labelOf(iconId: String?): String? = Countries.byCode(codeOf(iconId))?.let { "${it.flag} ${it.label}" }
}

/** Draws a profile icon id: a flag emoji for "flag:XX", otherwise the vector icon. */
@Composable
fun ProfileIconGlyph(
    iconId: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    val code = FlagIcons.codeOf(iconId)
    if (code != null) {
        FlagGlyph(code, modifier, size)
        return
    }
    BackdropIcon(
        imageVector = ProfileIcons.getIconById(iconId) ?: fallback,
        contentDescription = null,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/**
 * A flag emoji centred by its own pixels. Set as text, an emoji sits low in its line and looks
 * dropped next to the name beside it; drawn by its measured bounds it shares their centre line.
 */
@Composable
fun FlagGlyph(code: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val emoji = remember(code) { flagEmoji(code) }
    val textSize = with(LocalDensity.current) { (size * 0.86f).toPx() }
    // Emoji keep their own colours; they scale by text size, not by tint.
    val paint = remember(textSize) { Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize } }
    val ink = remember(emoji, paint) { Rect().also { paint.getTextBounds(emoji, 0, emoji.length, it) } }
    val label = remember(code) { Countries.byCode(code)?.label ?: code }
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(emoji, center.x - ink.exactCenterX(), center.y - ink.exactCenterY(), paint)
        }
    }
}
