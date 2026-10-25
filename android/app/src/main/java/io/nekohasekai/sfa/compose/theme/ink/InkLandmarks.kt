package io.nekohasekai.sfa.compose.theme.ink

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acosh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.random.Random

/**
 * The works of people in the scenes, drawn the way ink painting draws buildings (architectural ink painting): ruled
 * outlines filled with ink that is darkest high up and pales toward the foot, the page's
 * brushwork laid into them, and mist or water where they meet the ground.
 */
internal object InkLandmarks {
    fun draw(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        when (placed.landmark) {
            Landmark.Liberty -> liberty(canvas, placed, w, h, palette)
            Landmark.GoldenGate -> goldenGate(canvas, placed, w, h, palette)
            Landmark.EmpireState -> empireState(canvas, placed, w, h, palette)
            Landmark.Skyline -> skyline(canvas, placed, w, h, palette)
            Landmark.Capitol -> capitol(canvas, placed, w, h, palette)
            Landmark.Rushmore -> rushmore(canvas, placed, w, h, palette)
            Landmark.Niagara -> niagara(canvas, placed, w, h, palette)
            Landmark.BrooklynBridge -> brooklynBridge(canvas, placed, w, h, palette)
            Landmark.WashingtonMonument -> washingtonMonument(canvas, placed, w, h, palette)
            Landmark.ElmRows -> elmRows(canvas, placed, w, h, palette)
            Landmark.GatewayArch -> gatewayArch(canvas, placed, w, h, palette)
            Landmark.ColoradoRiver -> coloradoRiver(canvas, placed, w, h, palette)
            Landmark.Geyser -> geyser(canvas, placed, w, h, palette)
            Landmark.Lodgepoles -> lodgepoles(canvas, placed, w, h, palette)
        }
    }

    /** A landmark's own units, x across from its centre and y up from its foot, put onto the page. */
    private class Frame(placed: Placed, w: Float, h: Float, unitsTall: Float) {
        val unit = placed.height * h / unitsTall
        val cx = placed.x * w
        val foot = placed.base * h
        fun x(u: Float) = cx + u * unit
        fun y(u: Float) = foot - u * unit

        fun poly(vararg points: Float): Path = Path().apply {
            moveTo(x(points[0]), y(points[1]))
            for (i in 2 until points.size step 2) lineTo(x(points[i]), y(points[i + 1]))
            close()
        }

        fun oval(left: Float, bottom: Float, right: Float, top: Float): Path = Path().apply { addOval(x(left), y(top), x(right), y(bottom), Path.Direction.CW) }
    }

    /** Pieces drawn as one shape, so where they overlap the ink does not double. */
    private fun union(vararg pieces: Path): Path = Path().apply { pieces.forEach { op(it, Path.Op.UNION) } }

    private fun blurFor(placed: Placed, w: Float) = if (placed.far) w * 0.0018f else w * 0.0008f

    private fun line(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = width; this.color = color
        maskFilter = BlurMaskFilter(max(0.4f, width * 0.5f), BlurMaskFilter.Blur.NORMAL)
    }

    /**
     * Statue of Liberty from the harbour, in metres: Fort Wood's star walls, the stepped foundation, the
     * granite pedestal with its balcony and cornice, and on it the statue, the torch raised in her
     * right hand (on our left), the seven rays of the crown, the tablet in the crook of her left arm.
     */
    private fun liberty(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val f = Frame(placed, w, h, 93f)
        val blur = blurFor(placed, w)
        val heel = 47.2f
        val stone = union(
            f.poly(-24f, 0f, 24f, 0f, 22.5f, 5f, 19f, 6f, 19f, 7f, -19f, 7f, -19f, 6f, -22.5f, 5f),
            f.poly(-14f, 6.8f, 14f, 6.8f, 14f, 9.2f, 12.6f, 9.2f, 12.6f, 13.4f, 11.6f, 13.4f, 11f, 20.2f, -11f, 20.2f, -11.6f, 13.4f, -12.6f, 13.4f, -12.6f, 9.2f, -14f, 9.2f),
            f.poly(
                -10f, 20f, 10f, 20f, 10f, 22.4f, 9f, 23f, 8.4f, 37f, 8.8f, 37.4f, 8.8f, 40.2f, 9.6f, 41f, 9.6f, 42.2f, 7.6f, 42.6f, 7.6f, 46.3f, 8f, 46.7f, 8f, heel,
                -8f, heel, -8f, 46.7f, -7.6f, 46.3f, -7.6f, 42.6f, -9.6f, 42.2f, -9.6f, 41f, -8.8f, 40.2f, -8.8f, 37.4f, -8.4f, 37f, -9f, 23f, -10f, 22.4f,
            ),
        )
        InkPainter.inkShape(canvas, stone, f.y(heel), f.foot, placed.ink * 0.62f, palette, placed.far, blur, outline = w * 0.0009f, fadeTo = 0.22f)
        canvas.save()
        canvas.clipPath(stone)
        InkPainter.sideLight(canvas, f.x(-12f), f.x(12f), f.y(heel), f.foot, placed.ink, palette)
        // The courses of the granite ruled pale, and the balcony dark between its columns.
        val course = line(w * 0.0010f, palette.paper(0.36f))
        for (y in listOf(9.2f, 13.4f, 22.4f, 37.2f, 40.4f, 42.4f, 46.5f)) canvas.drawLine(f.x(-14f), f.y(y), f.x(14f), f.y(y), course)
        canvas.drawRect(f.x(-7.4f), f.y(40.0f), f.x(7.4f), f.y(37.7f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ink(placed.ink * 0.45f) })
        val column = line(w * 0.0014f, palette.paper(0.42f))
        for (k in -3..3) canvas.drawLine(f.x(k * 2.1f), f.y(39.8f), f.x(k * 2.1f), f.y(37.9f), column)
        canvas.restore()

        val figure = union(
            // Her robe and arms; the raised right arm is on our left.
            f.poly(
                -5.4f, heel, -5.0f, 55f, -4.6f, 62f, -4.9f, 68f, -5.4f, 72f, -5.7f, 74.5f,
                -5.9f, 78f, -5.6f, 82f, -5.2f, 85.4f,
                -5.5f, 86f, -5.4f, 87.3f, -3.9f, 87.3f, -3.8f, 86f,
                -4.0f, 85.4f, -3.6f, 82f, -3.2f, 78f, -2.9f, 75.6f,
                -1.4f, 76.2f, 1.4f, 76.2f,
                3.6f, 75.6f, 4.6f, 74.6f, 5.1f, 72f, 5.6f, 67f,
                5.4f, 64f, 5.0f, 60f, 5.1f, 54f, 5.4f, heel,
            ),
            // The tablet, tilted against her side.
            f.poly(5.3f, 61.4f, 7.1f, 62.2f, 6.1f, 69.6f, 4.6f, 69.0f),
            f.oval(-2.5f, 76.3f, 2.1f, 82.1f),
            crown(f, -0.2f, 80.4f),
            // The torch's handle and the railed gallery round the flame.
            f.poly(-4.95f, 87.2f, -4.25f, 87.2f, -4.3f, 88.7f, -4.9f, 88.7f),
            f.poly(-5.9f, 88.6f, -3.3f, 88.6f, -3.5f, 89.5f, -5.7f, 89.5f),
        )
        InkPainter.inkShape(canvas, figure, f.y(93f), f.y(heel), placed.ink, palette, placed.far, blur, outline = w * 0.0011f, fadeTo = 0.8f)
        canvas.save()
        canvas.clipPath(figure)
        InkPainter.sideLight(canvas, f.x(-6f), f.x(7.2f), f.y(93f), f.y(heel), placed.ink, palette)
        // The robe's folds falling from the waist, and the mantle across her from the left shoulder.
        val fold = line(w * 0.0012f, palette.paper(0.30f))
        for (x in listOf(-3.6f, -2.2f, -0.8f, 0.6f, 2.0f, 3.4f)) {
            canvas.drawPath(Path().apply { moveTo(f.x(x * 0.8f), f.y(66f)); quadTo(f.x(x * 0.95f), f.y(57f), f.x(x), f.y(heel + 0.5f)) }, fold)
        }
        canvas.drawPath(Path().apply { moveTo(f.x(4.2f), f.y(74.5f)); quadTo(f.x(0.5f), f.y(67f), f.x(-4.8f), f.y(61f)) }, line(w * 0.0016f, palette.paper(0.34f)))
        canvas.restore()
        // The flame is gilded: it holds the light.
        val flame = Path().apply {
            moveTo(f.x(-5.4f), f.y(89.5f))
            cubicTo(f.x(-6.0f), f.y(90.8f), f.x(-5.0f), f.y(91.9f), f.x(-4.55f), f.y(93f))
            cubicTo(f.x(-4.3f), f.y(91.9f), f.x(-3.3f), f.y(90.8f), f.x(-3.8f), f.y(89.5f))
            close()
        }
        val glowX = f.x(-4.6f)
        val glowY = f.y(91f)
        val glow = f.unit * 7f
        canvas.drawCircle(glowX, glowY, glow, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(glowX, glowY, glow, palette.bright(0.55f), palette.bright(0f), Shader.TileMode.CLAMP) })
        canvas.drawPath(flame, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.bright(0.96f) })
        canvas.drawPath(flame, line(w * 0.0011f, palette.ink(placed.ink * 0.9f)))
    }

    /** The crown's seven rays, fanned out round her head. */
    private fun crown(f: Frame, cx: Float, cy: Float): Path {
        val rays = Path()
        for (ray in 0 until 7) {
            val angle = Math.toRadians(18.0 + ray * 24.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            rays.op(f.poly(cx + dx * 2.2f - dy * 0.55f, cy + dy * 2.2f + dx * 0.55f, cx + dx * 5.8f, cy + dy * 5.8f, cx + dx * 2.2f + dy * 0.55f, cy + dy * 2.2f - dx * 0.55f), Path.Op.UNION)
        }
        return rays
    }

    /** The Golden Gate in metres: x along the bridge from the north tower, y across it, z up from the water. */
    private object Bridge {
        const val SPAN = 1280f
        const val SIDE = 343f
        const val TOP = 227f
        const val DECK = 67f
        const val LOW = 75f
        const val ANCHOR = 62f
        const val HALF = 16f
        const val TRUSS = 7.6f

        /** A leg's tiers, stepping in at each strut: from z, to z, half its width across, half its depth along. */
        val TIERS = listOf(
            floatArrayOf(-6f, 100f, 5.2f, 8.4f), floatArrayOf(100f, 138f, 4.8f, 7.6f), floatArrayOf(138f, 176f, 4.4f, 6.8f),
            floatArrayOf(176f, 214f, 4.0f, 6.0f), floatArrayOf(214f, TOP, 3.5f, 5.2f),
        )

        /** The portal struts above the deck: their height and half their depth. */
        val STRUTS = listOf(100f to 3.4f, 138f to 3.4f, 176f to 3.4f, 216f to 5.5f)

        fun cableZ(x: Float): Float = when {
            x < 0f -> sideSpan(-x / SIDE)
            x > SPAN -> sideSpan((x - SPAN) / SIDE)
            else -> ((x - SPAN / 2f) / (SPAN / 2f)).let { LOW + (TOP - LOW) * it * it }
        }

        private fun sideSpan(t: Float) = TOP + (ANCHOR - TOP) * t - 56f * t * (1f - t)
    }

    /**
     * The eye on the Marin headlands, north-west of the north tower and a little above the deck,
     * looking down the strait; scaled so the near tower stands where it is placed.
     */
    private class BridgeView(placed: Placed, w: Float, h: Float) {
        private val eyeX = -620f
        private val eyeY = -220f
        private val eyeZ = 95f
        private val forwardX: Float
        private val forwardY: Float
        private val scale: Float
        private val originX: Float
        private val horizon: Float

        init {
            val length = hypot(1320f, 220f)
            forwardX = 1320f / length
            forwardY = 220f / length
            val deck = raw(0f, 0f, Bridge.DECK)
            val top = raw(0f, 0f, Bridge.TOP)
            scale = placed.height * h / (deck.y - top.y)
            originX = placed.x * w - scale * deck.x
            horizon = placed.base * h - scale * deck.y
        }

        fun depth(x: Float, y: Float) = (x - eyeX) * forwardX + (y - eyeY) * forwardY

        private fun raw(x: Float, y: Float, z: Float): PointF {
            val depth = depth(x, y)
            return PointF(((x - eyeX) * forwardY - (y - eyeY) * forwardX) / depth, (eyeZ - z) / depth)
        }

        fun at(x: Float, y: Float, z: Float): PointF = raw(x, y, z).let { PointF(originX + scale * it.x, horizon + scale * it.y) }

        fun pixels(metres: Float, x: Float, y: Float) = scale * metres / depth(x, y)

        /** A flat face through the given corners, each as x, y, z. */
        fun face(vararg corners: Float): Path = Path().apply {
            for (i in corners.indices step 3) at(corners[i], corners[i + 1], corners[i + 2]).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
    }

    /**
     * Golden Gate Bridge from the Marin headlands: the near tower's portal with its struts, the far tower
     * smaller down the strait, the deck and both main cables receding with their hangers, and
     * fog off the ocean filling the strait under the deck, the towers rising out of it.
     */
    private fun goldenGate(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val view = BridgeView(placed, w, h)
        val ink = placed.ink
        fog(canvas, view, palette, behind = true)
        tower(canvas, view, Bridge.SPAN, listOf(Bridge.HALF, -Bridge.HALF), ink * 0.66f, palette, struts = true)
        tower(canvas, view, 0f, listOf(Bridge.HALF), ink, palette, struts = true)
        hangers(canvas, view, Bridge.HALF, ink * 0.30f, palette, w)
        cable(canvas, view, Bridge.HALF, ink * 0.72f, palette)
        deck(canvas, view, ink, palette, w)
        hangers(canvas, view, -Bridge.HALF, ink * 0.42f, palette, w)
        cable(canvas, view, -Bridge.HALF, ink * 0.92f, palette)
        tower(canvas, view, 0f, listOf(-Bridge.HALF), ink, palette, struts = false)
        fog(canvas, view, palette, behind = false)
    }

    private fun tower(canvas: Canvas, view: BridgeView, at: Float, legs: List<Float>, ink: Float, palette: InkPainter.Palette, struts: Boolean) {
        val front = Path()
        val side = Path()
        for (leg in legs) {
            for (tier in Bridge.TIERS) {
                val (z0, z1, across, along) = tier
                // The north face, toward us, and the west face, turned away from the light.
                front.op(view.face(at - along, leg - across, z0, at - along, leg + across, z0, at - along, leg + across, z1, at - along, leg - across, z1), Path.Op.UNION)
                side.op(view.face(at - along, leg - across, z0, at + along, leg - across, z0, at + along, leg - across, z1, at - along, leg - across, z1), Path.Op.UNION)
            }
        }
        if (struts) {
            val inner = Bridge.HALF - 4f
            for ((z, half) in Bridge.STRUTS) front.op(view.face(at - 4f, -inner, z - half, at - 4f, inner, z - half, at - 4f, inner, z + half, at - 4f, -inner, z + half), Path.Op.UNION)
        }
        // Dark from the top down to the deck, then thinning into the fog toward the water.
        val top = view.at(at, 0f, Bridge.TOP).y
        val deck = view.at(at, 0f, Bridge.DECK).y
        val foot = view.at(at, 0f, 6f).y
        val fade = LinearGradient(
            0f, top, 0f, foot,
            intArrayOf(palette.ink(ink), palette.ink(ink * 0.9f), palette.ink(ink * 0.5f), palette.ink(0f)),
            floatArrayOf(0f, (deck - top) / (foot - top), ((deck - top) / (foot - top) + 1f) / 2f, 1f), Shader.TileMode.CLAMP,
        )
        val soft = BlurMaskFilter(max(0.4f, view.pixels(0.5f, at, 0f)), BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(front, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = fade; maskFilter = soft })
        canvas.drawPath(side, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = fade; maskFilter = soft })
        InkPainter.grain(canvas, front, ink, palette, false, (at * 7f).toInt() + legs.size)
        canvas.drawPath(side, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, foot, palette.ink(ink * 0.45f), palette.ink(0f), Shader.TileMode.CLAMP)
            maskFilter = soft
        })
        // The legs are fluted: a pale line or two up each face, where they are near enough to see.
        if (view.pixels(10f, at, 0f) > 14f) {
            val flute = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(0.6f, view.pixels(0.5f, at, 0f)); color = palette.paper(0.16f) }
            for (leg in legs) for (offset in listOf(-0.35f, 0.35f)) {
                val tier = Bridge.TIERS[1]
                val from = view.at(at - tier[3], leg + tier[2] * offset, Bridge.DECK)
                val to = view.at(at - Bridge.TIERS[4][3], leg + Bridge.TIERS[4][2] * offset, Bridge.TOP - 4f)
                canvas.drawLine(from.x, from.y, to.x, to.y, flute)
            }
        }
    }

    /** The deck's stiffening truss, a dark band with the road's edge pale along its top. */
    private fun deck(canvas: Canvas, view: BridgeView, ink: Float, palette: InkPainter.Palette, w: Float) {
        val y = -Bridge.HALF + 2f
        val stations = generateSequence(-330f) { it + 15f }.takeWhile { it <= Bridge.SPAN + Bridge.SIDE + 260f }.toList()
        val band = Path().apply {
            stations.forEachIndexed { index, x -> view.at(x, y, Bridge.DECK).let { if (index == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            stations.asReversed().forEach { x -> view.at(x, y, Bridge.DECK - Bridge.TRUSS).let { lineTo(it.x, it.y) } }
            close()
        }
        canvas.drawPath(band, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ink(ink * 0.9f); maskFilter = BlurMaskFilter(max(0.4f, w * 0.0007f), BlurMaskFilter.Blur.NORMAL) })
        // The truss's verticals, where they are far enough apart to see.
        val post = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(0.6f, w * 0.0009f); color = palette.paper(0.30f) }
        var x = -330f
        var lastX = Float.NaN
        while (x < Bridge.SPAN + Bridge.SIDE) {
            val upper = view.at(x, y, Bridge.DECK - 0.8f)
            if (lastX.isNaN() || abs(upper.x - lastX) > 3.2f) {
                val lower = view.at(x, y, Bridge.DECK - Bridge.TRUSS + 0.8f)
                canvas.drawLine(upper.x, upper.y, lower.x, lower.y, post)
                lastX = upper.x
            }
            x += 7.6f
        }
        val edge = Path().apply { stations.forEachIndexed { index, s -> view.at(s, y, Bridge.DECK + 0.3f).let { if (index == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
        canvas.drawPath(edge, line(max(0.6f, w * 0.0010f), palette.paper(0.42f)))
    }

    /** A main cable as one ribbon, thick where it is near and thin far off. */
    private fun cable(canvas: Canvas, view: BridgeView, side: Float, ink: Float, palette: InkPainter.Palette) {
        val xs = generateSequence(-330f) { it + 8f }.takeWhile { it <= Bridge.SPAN + Bridge.SIDE }.toList()
        val points = xs.map { view.at(it, side, Bridge.cableZ(it)) }
        val widths = xs.map { max(0.7f, view.pixels(1.5f, it, side)) }
        val left = ArrayList<PointF>(points.size)
        val right = ArrayList<PointF>(points.size)
        for (i in points.indices) {
            val a = points[(i - 1).coerceAtLeast(0)]
            val b = points[(i + 1).coerceAtMost(points.size - 1)]
            val length = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
            val nx = -(b.y - a.y) / length * widths[i] / 2f
            val ny = (b.x - a.x) / length * widths[i] / 2f
            left += PointF(points[i].x + nx, points[i].y + ny)
            right += PointF(points[i].x - nx, points[i].y - ny)
        }
        val ribbon = Path().apply {
            moveTo(left[0].x, left[0].y)
            left.drop(1).forEach { lineTo(it.x, it.y) }
            right.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }
        canvas.drawPath(ribbon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ink(ink); maskFilter = BlurMaskFilter(0.5f, BlurMaskFilter.Blur.NORMAL) })
    }

    /** The hangers every fifteen metres, fading and thinning out as they recede. */
    private fun hangers(canvas: Canvas, view: BridgeView, side: Float, ink: Float, palette: InkPainter.Palette, w: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(0.6f, w * 0.0009f) }
        var lastX = Float.NaN
        var x = -Bridge.SIDE + 15.24f
        while (x < Bridge.SPAN + Bridge.SIDE - 5f) {
            if (abs(x) > 10f && abs(x - Bridge.SPAN) > 10f) {
                val top = view.at(x, side, Bridge.cableZ(x))
                if (lastX.isNaN() || abs(top.x - lastX) >= 2.6f) {
                    val bottom = view.at(x, side, Bridge.DECK)
                    paint.color = palette.ink(ink * (650f / view.depth(x, side)).coerceIn(0.35f, 1f))
                    canvas.drawLine(top.x, top.y, bottom.x, bottom.y, paint)
                    lastX = top.x
                }
            }
            x += 15.24f
        }
    }

    /**
     * Golden Gate in the mist: banks of fog lying on the water, behind the bridge and in front of it, their tops
     * kept under the deck so the deck and the towers stand clear above them.
     */
    private fun fog(canvas: Canvas, view: BridgeView, palette: InkPainter.Palette, behind: Boolean) {
        val deck = generateSequence(-330f) { it + 20f }.takeWhile { it <= Bridge.SPAN + Bridge.SIDE + 260f }.map { view.at(it, -Bridge.HALF, Bridge.DECK - Bridge.TRUSS) }.toList()
        val random = Random(if (behind) 1937 else 1938)
        repeat(if (behind) 24 else 28) {
            val along = -250f + random.nextFloat() * 2400f
            val across = if (behind) 40f + random.nextFloat() * 700f else -230f + random.nextFloat() * 210f
            if (view.depth(along, across) < 420f) return@repeat
            val centre = view.at(along, across, 4f + random.nextFloat() * 20f)
            val radius = view.pixels(150f + 170f * random.nextFloat(), along, across)
            val under = deck.minBy { abs(it.x - centre.x) }.y + (if (behind) -6f else 2f)
            if (centre.y <= under) return@repeat
            val squash = ((centre.y - under) / radius).coerceAtMost(if (behind) 0.26f else 0.22f)
            if (!behind) {
                // A faint grey under the bank, so it has a body and not only an edge.
                canvas.save()
                canvas.scale(1f, squash * 0.8f, centre.x, centre.y + radius * squash * 0.45f)
                canvas.drawCircle(centre.x, centre.y + radius * squash * 0.45f, radius * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(centre.x, centre.y + radius * squash * 0.45f, radius * 0.8f, palette.ink(0.07f), palette.ink(0f), Shader.TileMode.CLAMP)
                })
                canvas.restore()
            }
            InkPainter.mistBank(canvas, centre.x, centre.y, radius, squash, palette, 0.96f)
        }
    }

    /** Empire State Building: the broad base, the long shaft, the setbacks of its crown, the mast and the spire. */
    private fun empireState(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val f = Frame(placed, w, h, 4.4f)
        val shape = union(
            f.poly(-0.46f, 0f, 0.46f, 0f, 0.46f, 0.30f, -0.46f, 0.30f),
            f.poly(-0.36f, 0.28f, 0.36f, 0.28f, 0.36f, 0.62f, -0.36f, 0.62f),
            f.poly(-0.25f, 0.6f, 0.25f, 0.6f, 0.25f, 3.05f, -0.25f, 3.05f),
            f.poly(-0.19f, 3.0f, 0.19f, 3.0f, 0.19f, 3.24f, -0.19f, 3.24f),
            f.poly(-0.13f, 3.2f, 0.13f, 3.2f, 0.13f, 3.40f, -0.13f, 3.40f),
            f.poly(-0.08f, 3.38f, 0.08f, 3.38f, 0.06f, 3.6f, -0.06f, 3.6f),
            f.poly(-0.032f, 3.56f, 0.032f, 3.56f, 0.022f, 4.02f, -0.022f, 4.02f),
            f.poly(-0.008f, 4.0f, 0.008f, 4.0f, 0.004f, 4.4f, -0.004f, 4.4f),
        )
        InkPainter.inkShape(canvas, shape, f.y(4.4f), f.foot, placed.ink, palette, placed.far, w * 0.0010f, outline = 0f, textured = false, fadeTo = 0.15f)
        // Its piers run up the shaft in pale lines.
        val pier = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(0.6f, w * 0.0007f); color = palette.paper(0.20f) }
        for (k in -2..2) canvas.drawLine(f.x(k * 0.085f), f.y(2.98f), f.x(k * 0.085f), f.y(0.9f), pier)
    }

    /** Manhattan across the water: a paler row of towers behind, the nearer ones stepped or with spires. */
    private fun skyline(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val random = Random(1931)
        val foot = placed.base * h
        val span = w * 0.48f
        for (row in 0..1) {
            val shape = Path()
            val left = placed.x * w - span / 2f + (if (row == 0) w * 0.02f else 0f)
            var x = left
            while (x < left + span) {
                val width = w * (0.009f + 0.018f * random.nextFloat())
                val centre = 1f - abs((x + width / 2f - placed.x * w) / (span / 2f)).coerceAtMost(1f)
                val tall = if (row == 0) 1.1f else 1f
                val height = placed.height * h * tall * (0.2f + 0.8f * random.nextFloat() * (0.3f + 0.7f * centre))
                shape.op(Path().apply { addRect(x, foot - height, x + width, foot + 1f, Path.Direction.CW) }, Path.Op.UNION)
                when (random.nextInt(6)) {
                    0 -> shape.op(Path().apply { addRect(x + width * 0.22f, foot - height * 1.10f, x + width * 0.78f, foot - height + 1f, Path.Direction.CW) }, Path.Op.UNION)
                    1 -> shape.op(Path().apply { moveTo(x + width * 0.15f, foot - height + 1f); lineTo(x + width * 0.5f, foot - height * 1.2f); lineTo(x + width * 0.85f, foot - height + 1f); close() }, Path.Op.UNION)
                    2 -> shape.op(Path().apply { addRect(x + width * 0.46f, foot - height * 1.25f, x + width * 0.54f, foot - height + 1f, Path.Direction.CW) }, Path.Op.UNION)
                    else -> Unit
                }
                x += width * (if (row == 0) 0.9f + 0.5f * random.nextFloat() else 0.65f + 0.6f * random.nextFloat())
            }
            val ink = placed.ink * (if (row == 0) 0.55f else 1f)
            InkPainter.inkShape(canvas, shape, foot - placed.height * h * 1.3f, foot, ink, palette, placed.far, blurFor(placed, w) * (if (row == 0) 1.6f else 1f), outline = 0f, textured = false, fadeTo = 0.12f)
        }
    }

    /** Capitol: the long wings, the pedimented front, the drum ringed with columns, the dome and its lantern. */
    private fun capitol(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val f = Frame(placed, w, h, 0.47f)
        val dome = Path().apply {
            moveTo(f.x(-0.092f), f.y(0.24f))
            cubicTo(f.x(-0.092f), f.y(0.33f), f.x(-0.03f), f.y(0.365f), f.x(0f), f.y(0.365f))
            cubicTo(f.x(0.03f), f.y(0.365f), f.x(0.092f), f.y(0.33f), f.x(0.092f), f.y(0.24f))
            close()
        }
        val shape = union(
            f.poly(-0.5f, 0f, 0.5f, 0f, 0.5f, 0.09f, -0.5f, 0.09f),
            f.poly(-0.17f, 0.08f, 0.17f, 0.08f, 0.17f, 0.15f, -0.17f, 0.15f),
            f.poly(-0.08f, 0.145f, 0.08f, 0.145f, 0f, 0.18f),
            f.poly(-0.10f, 0.15f, 0.10f, 0.15f, 0.10f, 0.245f, -0.10f, 0.245f),
            dome,
            f.poly(-0.02f, 0.355f, 0.02f, 0.355f, 0.017f, 0.42f, -0.017f, 0.42f),
            f.poly(-0.005f, 0.415f, 0.005f, 0.415f, 0.003f, 0.47f, -0.003f, 0.47f),
        )
        InkPainter.inkShape(canvas, shape, f.y(0.47f), f.foot, placed.ink, palette, placed.far, blurFor(placed, w), outline = 0f, textured = false, fadeTo = 0.3f)
        val columns = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(0.6f, w * 0.0007f); color = palette.paper(0.22f) }
        for (k in -4..4) canvas.drawLine(f.x(k * 0.021f), f.y(0.235f), f.x(k * 0.021f), f.y(0.165f), columns)
    }

    /** Mount Rushmore: a granite crag broken into spires, four heads carved side by side in its face. */
    private fun rushmore(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val f = Frame(placed, w, h, 1.2f)
        val crag = Path().apply {
            moveTo(f.x(-1.4f), f.y(0f))
            var u = -1.4f
            while (u <= 1.4f) {
                val profile = (1f - (abs(u) / 1.4f).let { it * it }).let { if (it > 0f) Math.pow(it.toDouble(), 0.7).toFloat() else 0f } * 1.05f
                lineTo(f.x(u), f.y(max(0f, profile + InkPainter.fbm(u * 7f, 1941, 3) * 0.14f)))
                u += 0.035f
            }
            lineTo(f.x(1.4f), f.y(0f))
            close()
        }
        InkPainter.inkShape(canvas, crag, f.y(1.2f), f.foot, placed.ink, palette, placed.far, blurFor(placed, w), outline = 0f, fadeTo = 0.08f, seed = 41)
        // Washington, Jefferson, Roosevelt set back between them, Lincoln.
        val heads = listOf(Triple(-0.62f, 0.66f, 0.24f), Triple(-0.25f, 0.72f, 0.21f), Triple(0.07f, 0.63f, 0.18f), Triple(0.44f, 0.66f, 0.23f))
        val soft = BlurMaskFilter(max(0.6f, w * 0.0012f), BlurMaskFilter.Blur.NORMAL)
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = soft }
        heads.forEach { (hx, hy, r) ->
            canvas.drawPath(f.oval(hx - r * 0.66f, hy - r * 1.05f, hx + r * 0.66f, hy + r), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.paper(0.3f); maskFilter = soft })
            shade.color = palette.ink(placed.ink * 0.5f)
            // The eyes under the brow, the shadow beside the nose, the jaw's shadowed side.
            canvas.drawPath(f.oval(hx - r * 0.42f, hy + r * 0.08f, hx - r * 0.12f, hy + r * 0.26f), shade)
            canvas.drawPath(f.oval(hx + r * 0.10f, hy + r * 0.08f, hx + r * 0.40f, hy + r * 0.26f), shade)
            canvas.drawPath(f.poly(hx + r * 0.04f, hy + r * 0.05f, hx + r * 0.16f, hy - r * 0.42f, hx + r * 0.02f, hy - r * 0.45f), shade)
            shade.color = palette.ink(placed.ink * 0.35f)
            canvas.drawPath(f.oval(hx + r * 0.42f, hy - r * 1.0f, hx + r * 0.78f, hy + r * 0.5f), shade)
        }
    }

    /**
     * Niagara Falls as it is usually seen: the American Falls on the left, Goat Island dark with
     * trees, the Horseshoe Falls curving away on the right, the river grey above the brink, and
     * the spray of the Horseshoe rising out of its bend like a cloud.
     */
    private fun niagara(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val cx = placed.x * w
        val half = w * 0.26f
        val foot = placed.base * h
        val drop = placed.height * h
        val left = cx - half
        val right = cx + half
        val island = cx - half * 0.28f
        // The Horseshoe bends back: its middle sits further off, a little higher on the page.
        fun brink(x: Float): Float = if (x < island) foot - drop * 0.8f else {
            val t = ((x - island) / (right - island) * 2f - 1f).coerceIn(-1f, 1f)
            foot - drop - drop * 0.4f * (1f - t * t)
        }
        fun edge(path: Path, from: Float, to: Float) {
            for (k in 0..48) (from + (to - from) * k / 48f).let { path.lineTo(it, brink(it)) }
        }
        val upstream = foot - drop * 2.2f
        val river = Path().apply {
            moveTo(left, brink(left))
            edge(this, left, right)
            lineTo(right - half * 0.2f, upstream)
            lineTo(left + half * 0.3f, upstream)
            close()
        }
        canvas.drawPath(river, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, upstream, 0f, brink(cx), palette.deep(0f), palette.deep(0.2f), Shader.TileMode.CLAMP)
            maskFilter = BlurMaskFilter(w * 0.002f, BlurMaskFilter.Blur.NORMAL)
        })
        val flow = line(max(0.6f, w * 0.0010f), palette.deep(0.16f))
        val ripples = Random(1679)
        for (row in 1..4) {
            val y = upstream + (brink(cx) - upstream) * row / 5f
            repeat(3) {
                val x0 = left + half * 0.3f + (right - left - half * 0.6f) * ripples.nextFloat()
                canvas.drawLine(x0, y, x0 + w * (0.02f + 0.03f * ripples.nextFloat()) * row / 3f, y, flow)
            }
        }
        // The falling water, white and streaked, down to where the spray takes it.
        val falls = Path().apply {
            moveTo(left, brink(left))
            edge(this, left, right)
            lineTo(right, foot)
            lineTo(left, foot)
            close()
        }
        // White at the brink, thinning into the spray toward the foot.
        canvas.drawPath(falls, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, brink(cx), 0f, foot, palette.bright(0.97f), palette.bright(0.15f), Shader.TileMode.CLAMP) })
        canvas.save()
        canvas.clipPath(falls)
        val random = Random(1678)
        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(max(0.5f, w * 0.0011f), BlurMaskFilter.Blur.NORMAL) }
        var x = left
        while (x < right) {
            streak.strokeWidth = w * (0.0012f + 0.0022f * random.nextFloat())
            streak.color = palette.deep(0.06f + 0.16f * random.nextFloat())
            canvas.drawLine(x, brink(x) + drop * 0.15f * random.nextFloat(), x + (random.nextFloat() - 0.5f) * w * 0.003f, foot, streak)
            x += w * (0.0035f + 0.005f * random.nextFloat())
        }
        canvas.restore()
        canvas.drawPath(Path().apply { moveTo(left, brink(left)); edge(this, left, right) }, line(max(0.7f, w * 0.0013f), palette.deep(0.5f)))
        // Goat Island between the two falls, and the banks either side: dark with trees, the rock
        // showing under them where it drops to the water.
        val gap = half * 0.10f
        grove(canvas, island, brink(island) - drop * 0.15f, foot + drop * 0.3f, gap, placed.ink, palette, w, 1811)
        grove(canvas, left - half * 0.16f, brink(left) - drop * 0.1f, foot + drop * 0.6f, half * 0.2f, placed.ink, palette, w, 1812)
        grove(canvas, right + half * 0.16f, brink(right) - drop * 0.2f, foot + drop * 0.6f, half * 0.2f, placed.ink, palette, w, 1813)
        // Spray: low banks over the foot, and the Horseshoe's own cloud rising out of its bend.
        repeat(8) {
            val sx = left + (right - left) * random.nextFloat()
            InkPainter.mistBank(canvas, sx, foot - drop * 0.1f, w * (0.05f + 0.05f * random.nextFloat()), 0.55f, palette, 0.92f)
        }
        val plume = (island + right) / 2f + half * 0.08f
        repeat(5) { k ->
            InkPainter.mistBank(canvas, plume + (random.nextFloat() - 0.5f) * half * 0.3f, foot - drop * (0.6f + 0.55f * k), w * (0.07f - 0.008f * k), 0.75f, palette, 0.85f - 0.1f * k)
        }
    }

    // ---- The newer American scenes -------------------------------------------------------------

    /** The Brooklyn Bridge in metres: x along it from the Brooklyn tower, y across it, z up from the river. */
    private object Brooklyn {
        const val SPAN = 486f
        const val SIDE = 283f
        const val TOP = 84f
        const val DECK = 38f
        const val LOW = 46f
        const val ANCHOR = 30f

        /** Half the towers' width across the bridge, and half their depth along it. */
        const val HALF = 21f
        const val DEEP = 9f

        /** The outer cables, either side of the middle. */
        const val CABLE = 12.5f

        fun cableZ(x: Float): Float = when {
            x < 0f -> side(-x / SIDE)
            x > SPAN -> side((x - SPAN) / SIDE)
            else -> ((x - SPAN / 2f) / (SPAN / 2f)).let { LOW + (TOP - 2f - LOW) * it * it }
        }

        private fun side(t: Float) = (TOP - 2f) + (ANCHOR - TOP + 2f) * t - 18f * t * (1f - t)

        /**
         * One of the pointed arches through a tower, across (y) and up (z): straight jambs from the
         * roadway, then two arcs struck from the opposite springing, meeting at the point.
         */
        fun arch(centre: Float): List<Pair<Float, Float>> {
            val half = 5.1f
            val springing = 65f
            val sill = DECK - 2f
            val points = mutableListOf(centre - half to sill, centre - half to springing)
            for (k in 1..8) {
                val angle = Math.toRadians(180.0 - 60.0 * k / 8).toFloat()
                points += (centre + half + 2f * half * cos(angle)) to (springing + 2f * half * sin(angle))
            }
            for (k in 1..8) {
                val angle = Math.toRadians(60.0 - 60.0 * k / 8).toFloat()
                points += (centre - half + 2f * half * cos(angle)) to (springing + 2f * half * sin(angle))
            }
            points += centre + half to sill
            return points
        }
    }

    /**
     * A view in perspective, in metres (x along a bridge, y across it, z up from the water), from
     * an eye at a given place looking toward a given point, scaled so the tower at x = 0 stands
     * where it is placed: its deck at the placed base, its top the placed height above.
     */
    private class Perspective(
        placed: Placed, w: Float, h: Float,
        private val eyeX: Float, private val eyeY: Float, private val eyeZ: Float,
        lookX: Float, lookY: Float, deckZ: Float, topZ: Float,
    ) {
        private val forwardX: Float
        private val forwardY: Float
        private val scale: Float
        private val originX: Float
        private val horizon: Float

        init {
            val length = hypot(lookX - eyeX, lookY - eyeY)
            forwardX = (lookX - eyeX) / length
            forwardY = (lookY - eyeY) / length
            val deck = raw(0f, 0f, deckZ)
            val top = raw(0f, 0f, topZ)
            scale = placed.height * h / (deck.y - top.y)
            originX = placed.x * w - scale * deck.x
            horizon = placed.base * h - scale * deck.y
        }

        fun depth(x: Float, y: Float) = (x - eyeX) * forwardX + (y - eyeY) * forwardY

        private fun raw(x: Float, y: Float, z: Float): PointF {
            val depth = depth(x, y)
            return PointF(((x - eyeX) * forwardY - (y - eyeY) * forwardX) / depth, (eyeZ - z) / depth)
        }

        fun at(x: Float, y: Float, z: Float): PointF = raw(x, y, z).let { PointF(originX + scale * it.x, horizon + scale * it.y) }

        fun pixels(metres: Float, x: Float, y: Float) = scale * metres / depth(x, y)

        /** A flat face through the given corners, each as x, y, z. */
        fun face(vararg corners: Float): Path = Path().apply {
            for (i in corners.indices step 3) at(corners[i], corners[i + 1], corners[i + 2]).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
    }

    /**
     * Brooklyn Bridge from the Brooklyn shore, north of it, low by the water: the Brooklyn tower near
     * with its two pointed arches, the Manhattan tower down the river, the four cables slung
     * between them and the web of stays and hangers that is the bridge's own, and haze off the
     * river round the towers' feet. Lower Manhattan stands behind, across the water.
     */
    private fun brooklynBridge(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val view = Perspective(placed, w, h, -420f, 160f, 4f, Brooklyn.SPAN, 0f, Brooklyn.DECK, Brooklyn.TOP)
        val ink = placed.ink
        // The far things first, so the near ones lie over them.
        gothicTower(canvas, view, Brooklyn.SPAN, ink * 0.60f, palette, w)
        web(canvas, view, -Brooklyn.CABLE, ink * 0.26f, palette, w)
        mainCable(canvas, view, -Brooklyn.CABLE, ink * 0.55f, palette, w)
        brooklynDeck(canvas, view, ink, palette, w)
        web(canvas, view, Brooklyn.CABLE, ink * 0.40f, palette, w)
        gothicTower(canvas, view, 0f, ink, palette, w)
        mainCable(canvas, view, Brooklyn.CABLE, ink * 0.92f, palette, w)
        val near = view.at(0f, 0f, 0f)
        val far = view.at(Brooklyn.SPAN, 0f, 0f)
        InkPainter.mistBank(canvas, far.x, far.y, w * 0.20f, 0.30f, palette, 0.80f)
        InkPainter.mistBank(canvas, near.x, near.y, w * 0.32f, 0.24f, palette, 0.86f)
    }

    /** A granite tower: the side toward us in shade, the face with its two arches cut through, the cornice ruled pale. */
    private fun gothicTower(canvas: Canvas, view: Perspective, at: Float, ink: Float, palette: InkPainter.Palette, w: Float) {
        val front = at - Brooklyn.DEEP
        val back = at + Brooklyn.DEEP
        val half = Brooklyn.HALF
        val base = -2f
        val side = view.face(front, half, base, back, half, base, back, half, Brooklyn.TOP, front, half, Brooklyn.TOP)
        val face = view.face(front, -half, base, front, half, base, front, half, Brooklyn.TOP, front, -half, Brooklyn.TOP)
        for (centre in listOf(-9.6f, 9.6f)) {
            val opening = Path()
            Brooklyn.arch(centre).forEachIndexed { index, (y, z) -> view.at(front, y, z).let { if (index == 0) opening.moveTo(it.x, it.y) else opening.lineTo(it.x, it.y) } }
            opening.close()
            face.op(opening, Path.Op.DIFFERENCE)
        }
        val top = view.at(front, 0f, Brooklyn.TOP).y
        val foot = view.at(front, 0f, base).y
        val blur = view.pixels(0.25f, front, 0f)
        InkPainter.inkShape(canvas, side, top, foot, ink, palette, false, blur, outline = 0f, fadeTo = 0.30f, seed = 13)
        InkPainter.inkShape(canvas, face, top, foot, ink * 0.74f, palette, false, blur, outline = w * 0.0009f, fadeTo = 0.24f, seed = 17)
        canvas.save()
        canvas.clipPath(face)
        val course = line(max(0.6f, view.pixels(0.45f, front, 0f)), palette.paper(0.38f))
        for (z in listOf(76f, 80f, 82.5f)) {
            val a = view.at(front, -half, z)
            val b = view.at(front, half, z)
            canvas.drawLine(a.x, a.y, b.x, b.y, course)
        }
        canvas.restore()
    }

    /** The roadway's truss, from the Brooklyn anchorage to the Manhattan one, passing through both towers' arches. */
    private fun brooklynDeck(canvas: Canvas, view: Perspective, ink: Float, palette: InkPainter.Palette, w: Float) {
        val from = -Brooklyn.SIDE * 0.9f
        val to = Brooklyn.SPAN + Brooklyn.SIDE
        val edge = 13f
        val shape = view.face(from, edge, Brooklyn.DECK + 4f, to, edge, Brooklyn.DECK + 4f, to, edge, Brooklyn.DECK - 1.5f, from, edge, Brooklyn.DECK - 1.5f)
        val top = view.at(to, edge, Brooklyn.DECK + 4f).y
        val foot = view.at(from, edge, Brooklyn.DECK - 1.5f).y
        InkPainter.inkShape(canvas, shape, min(top, foot) - 1f, max(top, foot) + 1f, ink * 0.8f, palette, false, w * 0.0008f, outline = 0f, textured = false, fadeTo = 0.6f)
    }

    /** One of the main cables, slung from anchorage over both towers to anchorage. */
    private fun mainCable(canvas: Canvas, view: Perspective, side: Float, ink: Float, palette: InkPainter.Palette, w: Float) {
        val path = Path()
        var x = -Brooklyn.SIDE * 0.9f
        var first = true
        while (x <= Brooklyn.SPAN + Brooklyn.SIDE) {
            val p = view.at(x, side, Brooklyn.cableZ(x))
            if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            first = false
            x += 6f
        }
        canvas.drawPath(path, line(max(0.8f, view.pixels(0.9f, Brooklyn.SPAN / 2f, side)), palette.ink(ink)))
    }

    /** The web: hangers straight down from the cable, and the stays fanning from each tower's top to the roadway. */
    private fun web(canvas: Canvas, view: Perspective, side: Float, ink: Float, palette: InkPainter.Palette, w: Float) {
        val hanger = line(max(0.5f, w * 0.0006f), palette.ink(ink))
        var x = -Brooklyn.SIDE * 0.9f
        while (x <= Brooklyn.SPAN + Brooklyn.SIDE) {
            if (abs(x) > Brooklyn.DEEP && abs(x - Brooklyn.SPAN) > Brooklyn.DEEP) {
                val a = view.at(x, side, Brooklyn.cableZ(x))
                val b = view.at(x, side, Brooklyn.DECK + 3f)
                canvas.drawLine(a.x, a.y, b.x, b.y, hanger)
            }
            x += 9f
        }
        val stay = line(max(0.5f, w * 0.0007f), palette.ink(ink * 0.9f))
        for (tower in listOf(0f, Brooklyn.SPAN)) {
            for (direction in listOf(-1f, 1f)) {
                for (k in 1..11) {
                    val a = view.at(tower + direction * Brooklyn.DEEP, side, Brooklyn.TOP - 6f)
                    val b = view.at(tower + direction * (Brooklyn.DEEP + k * 13f), side, Brooklyn.DECK + 3f)
                    canvas.drawLine(a.x, a.y, b.x, b.y, stay)
                }
            }
        }
    }

    /**
     * Washington Monument from the Lincoln Memorial, in metres: the marble shaft tapering from 16.8 m to
     * 10.5 m over 152 m, the pyramidion to 169 m, the change in the stone a third of the way up
     * where building stopped for twenty years, the ring of flags round its foot on the knoll, and
     * the whole of it again, upside down and broken by ripples, in the Reflecting Pool.
     */
    private fun washingtonMonument(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val f = Frame(placed, w, h, 169.3f)
        val blur = blurFor(placed, w)
        val shaft = f.poly(-8.4f, 0f, 8.4f, 0f, 5.25f, 152.4f, 0f, 169.3f, -5.25f, 152.4f)
        // Its reflection first, under everything: in the pool, between its stone edges.
        val water = (placed.base + 0.024f) * h
        canvas.save()
        canvas.clipPath(Path().apply {
            moveTo(f.cx - w * 0.05f, water); lineTo(f.cx + w * 0.05f, water)
            lineTo(f.cx + w * 0.36f, h); lineTo(f.cx - w * 0.36f, h); close()
        })
        canvas.save()
        canvas.scale(1f, -1f, 0f, water)
        canvas.translate(0f, water - f.foot)
        InkPainter.inkShape(canvas, shaft, f.y(169.3f), f.foot, placed.ink * 0.32f, palette, true, blur * 3f, outline = 0f, textured = false, fadeTo = 0.6f)
        canvas.restore()
        // Ripples break it into bands.
        val random = Random(1884)
        val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = w * 0.0028f; color = palette.paper(0.75f); maskFilter = BlurMaskFilter(w * 0.0012f, BlurMaskFilter.Blur.NORMAL) }
        var y = water + h * 0.008f
        while (y < h) {
            val length = w * (0.02f + 0.06f * random.nextFloat()) * (1f + (y - water) / h * 3f)
            val x0 = f.cx + (random.nextFloat() - 0.5f) * w * 0.10f
            canvas.drawLine(x0 - length / 2f, y, x0 + length / 2f, y, ripple)
            y += h * (0.006f + 0.010f * random.nextFloat()) * (1f + (y - water) / h * 4f)
        }
        canvas.restore()

        // The knoll it stands on, and the monument.
        val knoll = Path().apply {
            moveTo(f.x(-160f), f.foot + f.unit * 2f)
            quadTo(f.x(0f), f.y(16f), f.x(160f), f.foot + f.unit * 2f)
            close()
        }
        InkPainter.inkShape(canvas, knoll, f.y(9f), f.foot + f.unit * 2f, placed.ink * 0.30f, palette, false, blur * 2f, outline = 0f, textured = false, fadeTo = 0.1f)
        InkPainter.inkShape(canvas, shaft, f.y(169.3f), f.y(8f), placed.ink, palette, placed.far, blur, outline = w * 0.0009f, fadeTo = 0.32f, seed = 19)
        canvas.save()
        canvas.clipPath(shaft)
        InkPainter.sideLight(canvas, f.x(-8.4f), f.x(8.4f), f.y(169.3f), f.foot, placed.ink, palette)
        // Above 46 m the marble came from another quarry: a paler stone below, a line where it changes.
        canvas.drawRect(f.x(-9f), f.y(46f), f.x(9f), f.foot, Paint().apply { color = palette.paper(0.10f) })
        canvas.drawLine(f.x(-9f), f.y(46f), f.x(9f), f.y(46f), line(w * 0.0011f, palette.paper(0.36f)))
        // The ridge of the pyramidion catching the light.
        canvas.drawLine(f.x(0f), f.y(169.3f), f.x(0f), f.y(152.4f), line(w * 0.0008f, palette.paper(0.42f)))
        canvas.restore()
        // Fifty flags in a ring round its foot: short masts, each with a flick of a flag.
        val mast = line(max(0.6f, w * 0.0006f), palette.ink(placed.ink * 0.75f))
        var x = -84f
        while (x <= 84f) {
            if (abs(x) > 11f) {
                val ground = 13f * (1f - (x / 160f) * (x / 160f))
                canvas.drawLine(f.x(x), f.y(ground), f.x(x), f.y(ground + 9f), mast)
                canvas.drawLine(f.x(x), f.y(ground + 9f), f.x(x + 2.4f), f.y(ground + 8.4f), mast)
            }
            x += 8.4f
        }
    }

    /**
     * The elms along the Reflecting Pool, in two rows that run toward the monument: each tree a
     * crown of Foliage stippling dots, large and near at the page's foot, small far off by the monument.
     */
    private fun elmRows(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val vx = placed.x * w
        val vy = placed.base * h
        // The pool's stone edges, ruled faint toward the monument.
        val edge = line(max(0.6f, w * 0.0010f), palette.ink(placed.ink * 0.35f))
        canvas.drawLine(vx - w * 0.05f, vy + h * 0.015f, vx - w * 0.36f, h, edge)
        canvas.drawLine(vx + w * 0.05f, vy + h * 0.015f, vx + w * 0.36f, h, edge)
        for (k in 0 until 9) {
            val t = (k / 8f).pow(1.7f)
            val y = vy + (h * 1.05f - vy) * t
            val half = w * (0.018f + 0.17f * t)
            val tall = half * 1.15f
            val spread = w * (0.085f + 0.62f * t)
            val ink = placed.ink * (0.45f + 0.55f * t)
            grove(canvas, vx - spread, y - tall, y, half, ink, palette, w, 400 + k)
            grove(canvas, vx + spread, y - tall, y, half, ink, palette, w, 500 + k)
        }
    }

    /**
     * Gateway Arch in metres: the weighted catenary its centre line follows, 192 m high and as wide
     * (y = 231 - 38.9 cosh(x / 38.9)), its legs tapering from 16.5 m at the ground to 5.2 m at the
     * top; stainless steel, so pale where it faces the light and dark on its far curve.
     */
    private fun gatewayArch(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val f = Frame(placed, w, h, 195f)
        val a = 38.92f
        val c = 230.95f
        val reach = a * acosh(c / a)
        val outer = ArrayList<PointF>()
        val inner = ArrayList<PointF>()
        val centre = ArrayList<PointF>()
        val steps = 96
        for (i in 0..steps) {
            val x = -reach + 2f * reach * i / steps
            val y = (c - a * cosh(x / a)).coerceAtLeast(0f)
            val slope = -sinh(x / a)
            val length = hypot(1f, slope)
            val nx = -slope / length
            val ny = 1f / length
            val half = (16.5f + (5.2f - 16.5f) * (y / 192f)) / 2f
            outer += PointF(x + nx * half, (y + ny * half).coerceAtLeast(0f))
            inner += PointF(x - nx * half, (y - ny * half).coerceAtLeast(0f))
            centre += PointF(x, y)
        }
        val shape = Path().apply {
            outer.forEachIndexed { i, p -> if (i == 0) moveTo(f.x(p.x), f.y(p.y)) else lineTo(f.x(p.x), f.y(p.y)) }
            inner.asReversed().forEach { lineTo(f.x(it.x), f.y(it.y)) }
            close()
        }
        InkPainter.inkShape(canvas, shape, f.y(195f), f.foot, placed.ink * 0.82f, palette, false, blurFor(placed, w), outline = w * 0.0009f, textured = false, fadeTo = 0.55f)
        canvas.save()
        canvas.clipPath(shape)
        InkPainter.sideLight(canvas, f.x(-reach - 8f), f.x(reach + 8f), f.y(195f), f.foot, placed.ink, palette)
        // The steel's sheen: a bright line down the face that turns to the light, a dark one on the far leg.
        val sheen = Path()
        val shade = Path()
        centre.forEachIndexed { i, p ->
            val q = PointF(f.x(p.x), f.y(p.y))
            if (p.x <= 0f) { if (sheen.isEmpty) sheen.moveTo(q.x, q.y) else sheen.lineTo(q.x, q.y) }
            if (p.x >= 0f) { if (shade.isEmpty) shade.moveTo(q.x, q.y) else shade.lineTo(q.x, q.y) }
        }
        canvas.drawPath(sheen, line(w * 0.0030f, palette.paper(0.55f)))
        canvas.drawPath(shade, line(w * 0.0030f, palette.ink(placed.ink * 0.35f)))
        canvas.restore()
    }

    /** The Colorado at the bottom of the canyon: a thin bright ribbon winding across its floor, wider toward us. */
    private fun coloradoRiver(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val y0 = placed.base * h
        val upper = ArrayList<PointF>()
        val lower = ArrayList<PointF>()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = (0.18f + 0.64f * t) * w
            val y = y0 + sin(t * PI.toFloat() * 2.6f + 0.4f) * h * 0.012f + (t - 0.5f) * h * 0.012f
            val half = h * (0.0025f + 0.003f * sin(t * PI.toFloat()))
            upper += PointF(x, y - half)
            lower += PointF(x, y + half)
        }
        val ribbon = Path().apply {
            upper.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            lower.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }
        canvas.drawPath(ribbon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.bright(0.92f); maskFilter = BlurMaskFilter(max(0.5f, w * 0.0010f), BlurMaskFilter.Blur.NORMAL) })
        val bank = line(max(0.5f, w * 0.0008f), palette.ink(placed.ink * 0.6f, far = true))
        canvas.drawPath(Path().apply { lower.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }, bank)
    }

    /**
     * Old Faithful in eruption: the pale sinter mound, the white column of water some forty metres
     * high, and steam billowing far above it, leaning with the wind and thinning as it rises. The
     * steam is the paper itself on a light page, shaped only by a grey wash on its shaded side;
     * at night it holds the moonlight.
     */
    private fun geyser(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val foot = placed.base * h
        val cx = placed.x * w
        val tall = placed.height * h
        val mound = Path().apply {
            moveTo(cx - w * 0.17f, foot + h * 0.006f)
            quadTo(cx, foot - h * 0.034f, cx + w * 0.17f, foot + h * 0.006f)
            close()
        }
        canvas.drawPath(mound, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, foot - h * 0.03f, 0f, foot + h * 0.006f, palette.ink(placed.ink * 0.30f), palette.ink(placed.ink * 0.06f), Shader.TileMode.CLAMP)
            maskFilter = BlurMaskFilter(w * 0.004f, BlurMaskFilter.Blur.NORMAL)
        })
        val random = Random(1870)
        class Puff(val x: Float, val y: Float, val r: Float, val t: Float)
        val puffs = List(30) { i ->
            val t = i / 29f
            Puff(
                cx + w * (0.11f * t * t + (random.nextFloat() - 0.5f) * 0.05f * (0.4f + t)),
                foot - h * 0.03f - (tall - h * 0.03f) * (0.18f + 0.82f * t),
                w * (0.030f + 0.095f * t) * (0.7f + 0.5f * random.nextFloat()),
                t,
            )
        }
        // The shaded undersides first, then the bright bodies over them.
        puffs.forEach { p ->
            val sx = p.x + p.r * 0.20f
            val sy = p.y + p.r * 0.26f
            canvas.drawCircle(sx, sy, p.r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(sx, sy, p.r, palette.ink(placed.ink * 0.16f * (1f - 0.5f * p.t)), palette.ink(0f), Shader.TileMode.CLAMP)
            })
        }
        // The column of water, white and narrow, spreading as it rises.
        val column = Path().apply {
            moveTo(cx - w * 0.007f, foot - h * 0.028f)
            lineTo(cx - w * 0.026f, foot - tall * 0.34f)
            lineTo(cx + w * 0.030f, foot - tall * 0.34f)
            lineTo(cx + w * 0.008f, foot - h * 0.028f)
            close()
        }
        canvas.drawPath(column, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx - w * 0.03f, 0f, cx + w * 0.03f, 0f, intArrayOf(palette.bright(0.7f), palette.bright(0.98f), palette.ink(placed.ink * 0.12f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            maskFilter = BlurMaskFilter(w * 0.004f, BlurMaskFilter.Blur.NORMAL)
        })
        puffs.forEach { p ->
            canvas.drawCircle(p.x, p.y, p.r * 0.92f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(p.x, p.y, p.r * 0.92f, intArrayOf(palette.bright(0.96f * (1f - 0.45f * p.t)), palette.bright(0.7f * (1f - 0.45f * p.t)), palette.bright(0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
        }
    }

    /**
     * Lodgepole pines round the geyser basin: thin straight trunks with narrow, ragged crowns, a
     * paler, shorter row behind a darker one, and a clearing where the geyser stands.
     */
    private fun lodgepoles(canvas: Canvas, placed: Placed, w: Float, h: Float, palette: InkPainter.Palette) {
        val random = Random(1872)
        val foot = placed.base * h
        val tall = placed.height * h
        canvas.drawRect(0f, foot - h * 0.01f, w, foot + h * 0.06f, Paint().apply {
            shader = LinearGradient(0f, foot - h * 0.01f, 0f, foot + h * 0.06f, palette.ink(placed.ink * 0.22f), palette.ink(0f), Shader.TileMode.CLAMP)
        })
        for (row in 0..1) {
            var x = -0.02f * w
            while (x < 1.02f * w) {
                val clearing = abs(x / w - 0.56f) < (if (row == 0) 0.08f else 0.12f)
                if (!clearing) {
                    val height = tall * (if (row == 0) 0.55f else 1f) * (0.6f + 0.5f * random.nextFloat())
                    val base = foot - (if (row == 0) h * 0.014f else 0f)
                    val ink = placed.ink * (if (row == 0) 0.42f else 0.9f) * (0.75f + 0.25f * random.nextFloat())
                    lodgepole(canvas, x, base, height, palette.ink(ink), random, w)
                }
                x += w * (if (row == 0) 0.013f else 0.021f) * (0.6f + 0.8f * random.nextFloat())
            }
        }
    }

    /** One lodgepole: a straight trunk and a narrow crown of short ragged tiers, the top a single spike. */
    private fun lodgepole(canvas: Canvas, x: Float, base: Float, height: Float, color: Int, random: Random, w: Float) {
        val half = height * 0.085f
        val crownTop = base - height
        val crownFoot = base - height * 0.22f
        val crown = Path().apply {
            moveTo(x, crownTop)
            val tiers = 7
            for (k in 1..tiers) {
                val y = crownTop + (crownFoot - crownTop) * k / tiers
                val reach = half * (0.30f + 0.70f * k / tiers) * (0.75f + 0.5f * random.nextFloat())
                lineTo(x + reach, y)
                lineTo(x + reach * 0.35f, y - (crownFoot - crownTop) / tiers * 0.35f)
            }
            lineTo(x, crownFoot)
            for (k in tiers downTo 1) {
                val y = crownTop + (crownFoot - crownTop) * k / tiers
                val reach = half * (0.30f + 0.70f * k / tiers) * (0.75f + 0.5f * random.nextFloat())
                lineTo(x - reach * 0.35f, y - (crownFoot - crownTop) / tiers * 0.35f)
                lineTo(x - reach, y)
            }
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; maskFilter = BlurMaskFilter(max(0.4f, w * 0.0007f), BlurMaskFilter.Blur.NORMAL) }
        canvas.drawPath(crown, paint)
        canvas.drawLine(x, crownFoot, x, base, line(max(0.6f, height * 0.018f), color))
    }

    /**
     * A bank of trees on rock, the way Foliage stippling paints woods: dots dense along the top for the crowns,
     * thinning as they go down over the rock, the rock itself a soft dark wash under them.
     */
    private fun grove(canvas: Canvas, cx: Float, top: Float, bottom: Float, half: Float, ink: Float, palette: InkPainter.Palette, w: Float, seed: Int) {
        canvas.drawOval(cx - half, top, cx + half, bottom + (bottom - top) * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, bottom, palette.ink(ink * 0.55f), palette.ink(0f), Shader.TileMode.CLAMP)
            maskFilter = BlurMaskFilter(w * 0.006f, BlurMaskFilter.Blur.NORMAL)
        })
        val random = Random(seed)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(max(0.5f, w * 0.0009f), BlurMaskFilter.Blur.NORMAL) }
        repeat((half / w * 900f).toInt().coerceIn(20, 140)) {
            val u = random.nextFloat() * 2f - 1f
            val down = random.nextFloat().pow(2.2f)
            val x = cx + u * half * (1f - 0.3f * down)
            val y = top + (bottom - top) * 0.15f * (u * u) + (bottom - top) * 0.8f * down
            dot.color = palette.ink(ink * (0.45f + 0.45f * random.nextFloat()) * (1f - 0.6f * down))
            canvas.drawCircle(x, y, w * (0.0018f + 0.0032f * random.nextFloat()), dot)
        }
    }
}
