package cc.txwd.kone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flags drawn as vectors on the preview's 20 by 14 box. Windows has no flag emoji (it shows the
 * two letters instead), so unlike the phone the flags are drawn here: every country the node
 * names are read for, simplified to what still reads at this size. A country without a drawing
 * keeps its empty place, so the names beside it still line up.
 */
object Flags {
    private val white = Color.White
    private val black = Color.Black

    private val drawings: Map<String, DrawScope.() -> Unit> = mapOf(
        "US" to {
            box(0f, 0f, 20f, 14f, white)
            for (k in 0..6) box(0f, k * 2.154f, 20f, 1.08f, Color(0xFFB22234))
            box(0f, 0f, 8.6f, 7.54f, Color(0xFF3C3B6E))
        },
        "GB" to { unionJack(0f, 0f, 20f, 14f) },
        "DE" to stripes(Color(0xFF000000), Color(0xFFDD0000), Color(0xFFFFCE00)),
        "JP" to {
            box(0f, 0f, 20f, 14f, white)
            dot(10f, 7f, 4.2f, Color(0xFFBC002D))
        },
        "SG" to {
            box(0f, 0f, 20f, 7f, Color(0xFFEF3340))
            box(0f, 7f, 20f, 7f, white)
            dot(5.2f, 3.5f, 2.5f, white)
            dot(6.2f, 3.5f, 2.3f, Color(0xFFEF3340))
            for (k in 0 until 5) {
                val a = -PI / 2 + k * 2 * PI / 5
                star(7.4f + 1.25f * cos(a).toFloat(), 3.6f + 1.25f * sin(a).toFloat(), .45f, white)
            }
        },
        "HK" to {
            box(0f, 0f, 20f, 14f, Color(0xFFDE2910))
            // The bauhinia: five white petals turning about the middle.
            for (k in 0 until 5) {
                val a = -PI / 2 + k * 2 * PI / 5
                petal(10f + 2.1f * cos(a).toFloat(), 7f + 2.1f * sin(a).toFloat(), 2.2f, 1.2f, (a * 180 / PI).toFloat() + 20f, white)
            }
        },
        "KR" to {
            box(0f, 0f, 20f, 14f, white)
            half(10f, 7f, 3.5f, top = true, Color(0xFFCD2E3A))
            half(10f, 7f, 3.5f, top = false, Color(0xFF0047A0))
            dot(8.25f, 7f, 1.75f, Color(0xFFCD2E3A))
            dot(11.75f, 7f, 1.75f, Color(0xFF0047A0))
            for ((x, y, turn) in listOf(Triple(3.6f, 2.6f, 33f), Triple(16.4f, 11.4f, 33f), Triple(16.4f, 2.6f, -33f), Triple(3.6f, 11.4f, -33f))) {
                for (k in -1..1) bar(x, y, 2.6f, .38f, k * .7f, turn, black)
            }
        },
        "NL" to stripes(Color(0xFFAE1C28), white, Color(0xFF21468B)),
        "FR" to bands(Color(0xFF002395), white, Color(0xFFED2939)),
        "CA" to {
            box(0f, 0f, 20f, 14f, white)
            box(0f, 0f, 5f, 14f, Color(0xFFD52B1E))
            box(15f, 0f, 5f, 14f, Color(0xFFD52B1E))
            leaf(10f, 7.2f, 3.4f, Color(0xFFD52B1E))
        },
        "AU" to {
            box(0f, 0f, 20f, 14f, Color(0xFF012169))
            unionJack(0f, 0f, 10f, 7f)
            star(5f, 10.5f, 1.6f, white, points = 7)
            for ((x, y, r) in listOf(Triple(15f, 3f, .8f), Triple(12.6f, 6.4f, .8f), Triple(17.4f, 5.6f, .8f), Triple(15f, 11.6f, .9f), Triple(16.2f, 7.6f, .45f))) {
                star(x, y, r, white, points = 7)
            }
        },
        "RU" to stripes(white, Color(0xFF0039A6), Color(0xFFD52B1E)),
        "IN" to {
            stripes(Color(0xFFFF9933), white, Color(0xFF138808))()
            ring(10f, 7f, 1.75f, .35f, Color(0xFF000080))
            dot(10f, 7f, .4f, Color(0xFF000080))
        },
        "BR" to {
            box(0f, 0f, 20f, 14f, Color(0xFF009C3B))
            shape(Color(0xFFFFDF00), 10f to 1.4f, 18.4f to 7f, 10f to 12.6f, 1.6f to 7f)
            dot(10f, 7f, 3f, Color(0xFF002776))
            bar(10f, 6.9f, 6f, .5f, 0f, -12f, white)
        },
        "TR" to {
            box(0f, 0f, 20f, 14f, Color(0xFFE30A17))
            dot(7.5f, 7f, 3.5f, white)
            dot(8.4f, 7f, 2.8f, Color(0xFFE30A17))
            star(11.6f, 7f, 1.4f, white, rotate = -18f)
        },
        "AE" to {
            stripes(Color(0xFF00732F), white, black)()
            box(0f, 0f, 5f, 14f, Color(0xFFFF0000))
        },
        "CH" to {
            box(0f, 0f, 20f, 14f, Color(0xFFDA291C))
            box(8.5f, 2.6f, 3f, 8.8f, white)
            box(5.6f, 5.5f, 8.8f, 3f, white)
        },
        "SE" to nordic(Color(0xFF006AA7), Color(0xFFFECC00)),
        "IT" to bands(Color(0xFF009246), white, Color(0xFFCE2B37)),
        "ES" to {
            box(0f, 0f, 20f, 14f, Color(0xFFAA151B))
            box(0f, 3.5f, 20f, 7f, Color(0xFFF1BF00))
            box(4.6f, 5.6f, 2.4f, 2.8f, Color(0xFFAA151B))
        },
        "PL" to {
            box(0f, 0f, 20f, 7f, white)
            box(0f, 7f, 20f, 7f, Color(0xFFDC143C))
        },
        "FI" to nordic(white, Color(0xFF002F6C)),
        "NO" to nordic(Color(0xFFBA0C2F), white, Color(0xFF00205B)),
        "IE" to bands(Color(0xFF169B62), white, Color(0xFFFF883E)),
        "AT" to stripes(Color(0xFFED2939), white, Color(0xFFED2939)),
        "BE" to bands(black, Color(0xFFFDDA24), Color(0xFFEF3340)),
        "DK" to nordic(Color(0xFFC8102E), white),
        "PT" to {
            box(0f, 0f, 8f, 14f, Color(0xFF006600))
            box(8f, 0f, 12f, 14f, Color(0xFFFF0000))
            ring(8f, 7f, 2.6f, .6f, Color(0xFFFFE900))
            box(6.9f, 5.4f, 2.2f, 3.2f, Color(0xFFFF0000))
            box(7.3f, 5.8f, 1.4f, 2.4f, white)
        },
        "CZ" to {
            box(0f, 0f, 20f, 7f, white)
            box(0f, 7f, 20f, 7f, Color(0xFFD7141A))
            shape(Color(0xFF11457E), 0f to 0f, 10f to 7f, 0f to 14f)
        },
        "UA" to {
            box(0f, 0f, 20f, 7f, Color(0xFF0057B7))
            box(0f, 7f, 20f, 7f, Color(0xFFFFD700))
        },
        "IL" to {
            box(0f, 0f, 20f, 14f, white)
            box(0f, 1.4f, 20f, 2.1f, Color(0xFF0038B8))
            box(0f, 10.5f, 20f, 2.1f, Color(0xFF0038B8))
            outline(Color(0xFF0038B8), .45f, 10f to 4.7f, 12.2f to 8.5f, 7.8f to 8.5f)
            outline(Color(0xFF0038B8), .45f, 10f to 9.3f, 7.8f to 5.5f, 12.2f to 5.5f)
        },
        "ZA" to {
            box(0f, 0f, 20f, 7f, Color(0xFFE03C31))
            box(0f, 7f, 20f, 7f, Color(0xFF001489))
            // The green Y lying on its side, edged in white, round a black triangle edged in gold.
            shape(white, 0f to 0f, 3f to 0f, 9.6f to 5.2f, 20f to 5.2f, 20f to 8.8f, 9.6f to 8.8f, 3f to 14f, 0f to 14f)
            shape(Color(0xFF007749), 0f to 1.2f, 1.6f to 1.2f, 8.8f to 6.1f, 20f to 6.1f, 20f to 7.9f, 8.8f to 7.9f, 1.6f to 12.8f, 0f to 12.8f)
            shape(Color(0xFFFFB81C), 0f to 2.6f, 6.1f to 7f, 0f to 11.4f)
            shape(black, 0f to 3.6f, 4.7f to 7f, 0f to 10.4f)
        },
        "MX" to {
            bands(Color(0xFF006847), white, Color(0xFFCE1126))()
            dot(10f, 7f, 1.5f, Color(0xFF8C6239))
            ring(10f, 7.3f, 1.9f, .35f, Color(0xFF3A7D44))
        },
        "AR" to {
            stripes(Color(0xFF74ACDF), white, Color(0xFF74ACDF))()
            dot(10f, 7f, 1.3f, Color(0xFFF6B40E))
        },
        "CL" to {
            box(0f, 0f, 20f, 7f, white)
            box(0f, 7f, 20f, 7f, Color(0xFFD52B1E))
            box(0f, 0f, 7f, 7f, Color(0xFF0039A6))
            star(3.5f, 3.5f, 1.8f, white)
        },
        "TH" to {
            box(0f, 0f, 20f, 14f, Color(0xFFA51931))
            box(0f, 14f / 6, 20f, 14f * 4 / 6, white)
            box(0f, 14f / 3, 20f, 14f / 3, Color(0xFF2D2A4A))
        },
        "VN" to {
            box(0f, 0f, 20f, 14f, Color(0xFFDA251D))
            star(10f, 7.3f, 4.2f, Color(0xFFFFFF00))
        },
        "MY" to {
            for (k in 0 until 14) box(0f, k.toFloat(), 20f, 1f, if (k % 2 == 0) Color(0xFFCC0001) else white)
            box(0f, 0f, 10f, 8f, Color(0xFF010066))
            dot(4.2f, 4f, 2.8f, Color(0xFFFFCC00))
            dot(5f, 4f, 2.4f, Color(0xFF010066))
            star(7.6f, 4f, 1.6f, Color(0xFFFFCC00), points = 14, inner = .55f)
        },
        "PH" to {
            box(0f, 0f, 20f, 7f, Color(0xFF0038A8))
            box(0f, 7f, 20f, 7f, Color(0xFFCE1126))
            shape(white, 0f to 0f, 12.1f to 7f, 0f to 14f)
            star(3.9f, 7f, 1.9f, Color(0xFFFCD116), points = 8, inner = .45f)
            for ((x, y) in listOf(1.2f to 1.6f, 1.2f to 12.4f, 10.3f to 7f)) star(x, y, .65f, Color(0xFFFCD116))
        },
        "ID" to {
            box(0f, 0f, 20f, 7f, Color(0xFFFF0000))
            box(0f, 7f, 20f, 7f, white)
        },
        "NZ" to {
            box(0f, 0f, 20f, 14f, Color(0xFF00247D))
            unionJack(0f, 0f, 10f, 7f)
            for ((x, y, r) in listOf(Triple(15.4f, 3.2f, .9f), Triple(13f, 6.6f, .8f), Triple(17.6f, 6f, .8f), Triple(15.4f, 11.2f, .95f))) {
                star(x, y, r + .35f, white)
                star(x, y, r, Color(0xFFCC142B))
            }
        },
        "MO" to {
            box(0f, 0f, 20f, 14f, Color(0xFF00785E))
            petal(10f, 7.2f, 1.2f, 2.4f, 0f, white)
            petal(8.5f, 7.6f, 1f, 2f, -40f, white)
            petal(11.5f, 7.6f, 1f, 2f, 40f, white)
            box(7.6f, 9.6f, 4.8f, .45f, white)
            box(8.2f, 10.4f, 3.6f, .4f, white)
            for (k in 0 until 5) {
                val a = PI + PI * (k + 1) / 6
                star(10f + 4.2f * cos(a).toFloat(), 6.6f + 3.4f * sin(a).toFloat(), if (k == 2) .8f else .5f, Color(0xFFFBD116))
            }
        },
        "LU" to stripes(Color(0xFFED2939), white, Color(0xFF00A1DE)),
        "RO" to bands(Color(0xFF002B7F), Color(0xFFFCD116), Color(0xFFCE1126)),
        "HU" to stripes(Color(0xFFCE2939), white, Color(0xFF477050)),
        "BG" to stripes(white, Color(0xFF00966E), Color(0xFFD62612)),
        "KZ" to {
            box(0f, 0f, 20f, 14f, Color(0xFF00AFCA))
            box(1.2f, 0f, .9f, 14f, Color(0xFFFEC50C))
            dot(10.5f, 5.8f, 2.2f, Color(0xFFFEC50C))
            bar(10.5f, 9.8f, 6.4f, .7f, 0f, 0f, Color(0xFFFEC50C))
        },
        "SA" to {
            box(0f, 0f, 20f, 14f, Color(0xFF006C35))
            // The creed as a pale band of writing, and the sword beneath it.
            for (k in 0 until 7) bar(5.4f + k * 1.55f, 5.6f, .5f, 2.4f, 0f, 0f, white)
            box(5f, 6.6f, 10f, .5f, white)
            bar(10f, 9.6f, 8.4f, .5f, 0f, 0f, white)
            box(5.4f, 9.1f, .5f, 1f, white)
        },
        "EG" to {
            stripes(Color(0xFFCE1126), white, black)()
            dot(10f, 7f, 1.1f, Color(0xFFC09300))
        },
        "NG" to bands(Color(0xFF008751), white, Color(0xFF008751)),
        "CN" to {
            box(0f, 0f, 20f, 14f, Color(0xFFDE2910))
            val gold = Color(0xFFFFDE00)
            star(3.4f, 3.5f, 2.1f, gold)
            for ((x, y, turn) in listOf(Triple(6.8f, 1.4f, 23f), Triple(8.1f, 2.8f, 46f), Triple(8.1f, 4.6f, 70f), Triple(6.8f, 5.9f, 21f))) star(x, y, .7f, gold, rotate = turn)
        },
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

    /** A Nordic cross set toward the hoist; with [inner], a cross edged in [cross]. */
    private fun nordic(field: Color, cross: Color, inner: Color? = null): DrawScope.() -> Unit = {
        box(0f, 0f, 20f, 14f, field)
        box(5.2f, 0f, 3.2f, 14f, cross)
        box(0f, 5.4f, 20f, 3.2f, cross)
        if (inner != null) {
            box(5.9f, 0f, 1.8f, 14f, inner)
            box(0f, 6.1f, 20f, 1.8f, inner)
        }
    }

    /** The Union Jack in the box at x, y, w by h (the whole flag, or a canton). */
    private fun DrawScope.unionJack(x: Float, y: Float, w: Float, h: Float) {
        val k = size.width / 20f
        val blue = Color(0xFF012169)
        val red = Color(0xFFC8102E)
        box(x, y, w, h, blue)
        clipRectUnits(x, y, w, h) {
            val diagonal = h * .2f
            drawLine(white, Offset(x * k, y * k), Offset((x + w) * k, (y + h) * k), diagonal * k)
            drawLine(white, Offset(x * k, (y + h) * k), Offset((x + w) * k, y * k), diagonal * k)
            drawLine(red, Offset(x * k, y * k), Offset((x + w) * k, (y + h) * k), diagonal * .4f * k)
            drawLine(red, Offset(x * k, (y + h) * k), Offset((x + w) * k, y * k), diagonal * .4f * k)
        }
        box(x + w / 2 - h / 6, y, h / 3, h, white)
        box(x, y + h / 3, w, h / 3, white)
        box(x + w / 2 - h / 10, y, h / 5, h, red)
        box(x, y + h / 2 - h / 10, w, h / 5, red)
    }

    private fun DrawScope.clipRectUnits(x: Float, y: Float, w: Float, h: Float, block: DrawScope.() -> Unit) {
        val k = size.width / 20f
        drawContext.canvas.save()
        drawContext.canvas.clipRect(x * k, y * k, (x + w) * k, (y + h) * k)
        block()
        drawContext.canvas.restore()
    }

    private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, color: Color) {
        val k = size.width / 20f
        drawRect(color, Offset(x * k, y * k), Size(w * k, h * k))
    }

    private fun DrawScope.dot(x: Float, y: Float, r: Float, color: Color) {
        val k = size.width / 20f
        drawCircle(color, r * k, Offset(x * k, y * k))
    }

    private fun DrawScope.ring(x: Float, y: Float, r: Float, width: Float, color: Color) {
        val k = size.width / 20f
        drawCircle(color, r * k, Offset(x * k, y * k), style = Stroke(width * k))
    }

    private fun DrawScope.half(x: Float, y: Float, r: Float, top: Boolean, color: Color) {
        val k = size.width / 20f
        drawArc(color, if (top) 180f else 0f, 180f, true, Offset((x - r) * k, (y - r) * k), Size(2 * r * k, 2 * r * k))
    }

    /** A filled outline through [points]. */
    private fun DrawScope.shape(color: Color, vararg points: Pair<Float, Float>) {
        val k = size.width / 20f
        drawPath(polygon(k, points.toList()), color)
    }

    private fun DrawScope.outline(color: Color, width: Float, vararg points: Pair<Float, Float>) {
        val k = size.width / 20f
        drawPath(polygon(k, points.toList()), color, style = Stroke(width * k))
    }

    private fun polygon(k: Float, points: List<Pair<Float, Float>>) = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * k, y * k) else lineTo(x * k, y * k) }
        close()
    }

    /** A star of [points] points, [inner] the depth of its notches; its first point up, turned [rotate] degrees. */
    private fun DrawScope.star(x: Float, y: Float, r: Float, color: Color, points: Int = 5, inner: Float = .382f, rotate: Float = 0f) {
        val k = size.width / 20f
        val path = Path()
        for (i in 0 until points * 2) {
            val a = -PI / 2 + i * PI / points + rotate * PI / 180
            val radius = if (i % 2 == 0) r else r * inner
            val px = (x + radius * cos(a).toFloat()) * k
            val py = (y + radius * sin(a).toFloat()) * k
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(path, color)
    }

    /** A bar w long and h thick, centred at x, y, moved [shift] across itself, turned [turn] degrees. */
    private fun DrawScope.bar(x: Float, y: Float, w: Float, h: Float, shift: Float, turn: Float, color: Color) {
        val k = size.width / 20f
        val a = turn * PI / 180
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val cx = x - uy * shift
        val cy = y + ux * shift
        drawLine(color, Offset((cx - ux * w / 2) * k, (cy - uy * w / 2) * k), Offset((cx + ux * w / 2) * k, (cy + uy * w / 2) * k), h * k, StrokeCap.Butt)
    }

    /** An oval petal rx by ry about x, y, turned [turn] degrees. */
    private fun DrawScope.petal(x: Float, y: Float, rx: Float, ry: Float, turn: Float, color: Color) {
        val k = size.width / 20f
        drawContext.canvas.save()
        drawContext.transform.rotate(turn, Offset(x * k, y * k))
        drawOval(color, Offset((x - rx / 2) * k, (y - ry / 2) * k), Size(rx * k, ry * k))
        drawContext.canvas.restore()
    }

    /** A maple leaf, its stem down, about x, y. */
    private fun DrawScope.leaf(x: Float, y: Float, r: Float, color: Color) {
        val points = listOf(
            0f to -1f, .18f to -.62f, .36f to -.72f, .3f to -.32f, .62f to -.5f, .56f to -.28f, .78f to -.32f, .62f to .02f,
            .74f to .1f, .36f to .36f, .4f to .52f, .06f to .48f, .06f to .86f, -.06f to .86f, -.06f to .48f, -.4f to .52f,
            -.36f to .36f, -.74f to .1f, -.62f to .02f, -.78f to -.32f, -.56f to -.28f, -.62f to -.5f, -.3f to -.32f, -.36f to -.72f, -.18f to -.62f,
        ).map { (px, py) -> x + px * r to y + py * r }
        shape(color, *points.toTypedArray())
    }

    internal fun draw(code: String): (DrawScope.() -> Unit)? = drawings[code]

    /** The countries with a flag. */
    internal val drawn: Set<String> get() = drawings.keys
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
