package cc.txwd.kone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The line drawings of the preview, as SVG path data on a 24-unit square unless noted. */
object Glyphs {
    val dashboard = listOf("M5 3.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z", "M15 3.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z", "M5 13.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z", "M15 13.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z")
    val log = listOf("M6 3.5h8.5L19 8v12.5H6z", "M14.5 3.5V8H19", "M9 12.5h7M9 16h7")
    val tools = listOf("M5 4.5h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-14a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z", "M7 10l3 2.5L7 15M12.5 15.5h4.5")
    val settings = listOf("M15.2 12a3.2 3.2 0 1 1-6.4 0a3.2 3.2 0 1 1 6.4 0", "M18.8 12a6.8 6.8 0 1 1-13.6 0a6.8 6.8 0 1 1 13.6 0", "M12 2.6v2.6M12 18.8v2.6M2.6 12h2.6M18.8 12h2.6M5.4 5.4l1.8 1.8M16.8 16.8l1.8 1.8M5.4 18.6l1.8-1.8M16.8 7.2l1.8-1.8")
    val power = listOf("M12 3.5v8", "M6.4 7.2a7.6 7.6 0 1 0 11.2 0")
    val paste = listOf("M9 3.5h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1z", "M8 5.5H6.5a1 1 0 0 0-1 1v13a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1v-13a1 1 0 0 0-1-1H16")
    val home = listOf("M4 11.5L12 4l8 7.5", "M6.5 10v9.5h11V10")
    val check = listOf("M5 12.5l4.2 4.2L19 7")

    /** The title bar's buttons, on a 12-unit square. */
    val minimize = listOf("M1 6h10")
    val maximize = listOf("M1.5 1.5h9v9h-9z")
    val close = listOf("M1.5 1.5l9 9M10.5 1.5l-9 9")

    /** The KOne mark, filled, on its 108-unit square (shifted up 4 as the preview draws it). */
    val mark = listOf(
        "M12,90 L27,72 L33,66 L43,63 L49,51 L53,45 L55,38 L60,33 L64,25 L68,28 L70,29 L74,40 L76,43 L78,49 L82,50 L88,62 L95,73 L105,90 L74,87 L52,86 L34,88 Z M66,31 L69,49 L82,76 L86,85 L73,69 L65,43 Z M52,52 L55,59 L58,75 L66,83 L51,70 L47,64 Z",
        "M52,41 H55 V39 H56 V41 H58 V44 H56 V43 H55 V45 H52 Z",
    )

    fun parse(data: List<String>, fill: PathFillType = PathFillType.NonZero): List<Path> =
        data.map { PathParser().parsePathString(it).toPath().apply { fillType = fill } }
}

/** A line drawing from [Glyphs], stroked in [color] with round ends, as the preview's SVGs are. */
@Composable
fun Glyph(data: List<String>, color: Color, size: Dp, strokeWidth: Float, modifier: Modifier = Modifier, box: Float = 24f) {
    val paths = remember(data) { Glyphs.parse(data) }
    Canvas(modifier.size(size)) {
        val k = this.size.width / box
        scale(k, k, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

/** The KOne mountain mark. */
@Composable
fun Mark(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val paths = remember { Glyphs.parse(Glyphs.mark, PathFillType.EvenOdd) }
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        scale(k, k, pivot = Offset.Zero) {
            translate(top = -4f) { paths.forEach { drawPath(it, color) } }
        }
    }
}

/** The three dots of the ⋮ menu, filled. */
@Composable
fun MoreDots(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        for (y in listOf(5.5f, 12f, 18.5f)) drawCircle(color, 1.7f * k, Offset(12f * k, y * k))
    }
}
