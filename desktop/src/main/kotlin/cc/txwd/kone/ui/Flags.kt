package cc.txwd.kone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Flags drawn as vectors on the preview's 20 by 14 box. Windows has no flag emoji (it shows the
 * two letters instead), so unlike the phone the flags are drawn here. A country without a
 * drawing keeps its empty place, so the names beside it still line up.
 */
object Flags {
    private val drawings: Map<String, DrawScope.() -> Unit> = mapOf(
        "US" to {
            box(0f, 0f, 20f, 14f, Color.White)
            for (k in 0..6) box(0f, k * 2.154f, 20f, 1.08f, Color(0xFFB22234))
            box(0f, 0f, 8.6f, 7.54f, Color(0xFF3C3B6E))
        },
        "JP" to {
            box(0f, 0f, 20f, 14f, Color.White)
            dot(10f, 7f, 4.2f, Color(0xFFBC002D))
        },
        "SG" to {
            box(0f, 0f, 20f, 7f, Color(0xFFEF3340))
            box(0f, 7f, 20f, 7f, Color.White)
            dot(5.2f, 3.5f, 2.5f, Color.White)
            dot(6.2f, 3.5f, 2.3f, Color(0xFFEF3340))
        },
        "DE" to stripes(Color(0xFF000000), Color(0xFFDD0000), Color(0xFFFFCE00)),
        "NL" to stripes(Color(0xFFAE1C28), Color.White, Color(0xFF21468B)),
        "RU" to stripes(Color.White, Color(0xFF0039A6), Color(0xFFD52B1E)),
        "FR" to bands(Color(0xFF002395), Color.White, Color(0xFFED2939)),
    )

    private fun stripes(top: Color, middle: Color, bottom: Color): DrawScope.() -> Unit = {
        box(0f, 0f, 20f, 14f / 3f, top)
        box(0f, 14f / 3f, 20f, 14f / 3f, middle)
        box(0f, 28f / 3f, 20f, 14f / 3f, bottom)
    }

    private fun bands(left: Color, middle: Color, right: Color): DrawScope.() -> Unit = {
        box(0f, 0f, 20f / 3f, 14f, left)
        box(20f / 3f, 0f, 20f / 3f, 14f, middle)
        box(40f / 3f, 0f, 20f / 3f, 14f, right)
    }

    private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, color: Color) {
        val k = size.width / 20f
        drawRect(color, Offset(x * k, y * k), Size(w * k, h * k))
    }

    private fun DrawScope.dot(x: Float, y: Float, r: Float, color: Color) {
        val k = size.width / 20f
        drawCircle(color, r * k, Offset(x * k, y * k))
    }

    internal fun draw(code: String): (DrawScope.() -> Unit)? = drawings[code]
}

/** A flag the size the preview gives it: 20 by 14 in a row of nodes, 26 by 18 as the big one. */
@Composable
fun Flag(code: String?, width: Dp = 20.dp, height: Dp = 14.dp) {
    val drawing = code?.let { Flags.draw(it) }
    if (drawing == null) {
        Spacer(Modifier.size(width, height))
        return
    }
    Canvas(Modifier.size(width, height).clip(RoundedCornerShape(2.dp))) {
        drawing()
        // The preview's hairline ring, so a white flag still has an edge on white paper.
        drawRoundRect(Color.Black.copy(alpha = 0.1f), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(width = 2f))
    }
}
