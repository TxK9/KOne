package cc.txwd.kone.ink

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathOp
import org.jetbrains.skia.Shader

/*
 * The few pieces of android.graphics the phone's ink painter leans on, in Skia's terms, so the
 * painter reads the same here as on the phone (android.graphics is Skia underneath).
 */

/** A paint with anti-aliasing on, as Paint(Paint.ANTI_ALIAS_FLAG) gives on the phone. */
internal fun paint(block: Paint.() -> Unit = {}): Paint = Paint().apply { isAntiAlias = true; block() }

/** A plain paint without anti-aliasing, for rectangles and washes. */
internal fun plain(block: Paint.() -> Unit = {}): Paint = Paint().apply { isAntiAlias = false; block() }

internal fun Paint.stroke(width: Float, round: Boolean = true) {
    mode = PaintMode.STROKE
    strokeWidth = width
    if (round) {
        strokeCap = PaintStrokeCap.ROUND
        strokeJoin = PaintStrokeJoin.ROUND
    }
}

/** BlurMaskFilter(radius, NORMAL): Android turns its radius into Skia's sigma this way. */
internal fun blur(radius: Float): MaskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 0.57735f * radius + 0.5f, true)

private fun gradient(colors: IntArray, stops: FloatArray?): Gradient =
    Gradient(Gradient.Colors(Array(colors.size) { Color4f(colors[it]) }, stops, FilterTileMode.CLAMP, null), Gradient.Interpolation())

internal fun linear(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray? = null): Shader =
    Shader.makeLinearGradient(x0, y0, x1, y1, gradient(colors, stops))

internal fun linear(x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int): Shader = linear(x0, y0, x1, y1, intArrayOf(from, to))

internal fun radial(x: Float, y: Float, radius: Float, colors: IntArray, stops: FloatArray? = null): Shader =
    Shader.makeRadialGradient(x, y, radius, gradient(colors, stops))

internal fun radial(x: Float, y: Float, radius: Float, from: Int, to: Int): Shader = radial(x, y, radius, intArrayOf(from, to))

/** ComposeShader(dst, src, DST_IN): the first shader kept only where the second is opaque. */
internal fun masked(dst: Shader, src: Shader): Shader = Shader.makeBlend(BlendMode.DST_IN, dst, src)

internal fun path(block: PathBuilder.() -> Unit): Path = PathBuilder().apply(block).detach()

/** Pieces drawn as one shape, so where they overlap the ink does not double. */
internal fun union(vararg pieces: Path): Path = pieces.reduce { a, b -> Path.makeCombining(a, b, PathOp.UNION) ?: a }

internal fun union(pieces: List<Path>): Path = union(*pieces.toTypedArray())

/** canvas.scale(sx, sy, px, py) on the phone: scale about a point. */
internal fun Canvas.scaleAbout(sx: Float, sy: Float, px: Float, py: Float) {
    translate(px, py)
    scale(sx, sy)
    translate(-px, -py)
}

internal fun Canvas.clip(path: Path) {
    clipPath(path, org.jetbrains.skia.ClipMode.INTERSECT, true)
}
