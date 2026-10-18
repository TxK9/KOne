package cc.txwd.kone.ink

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Paints one of [InkMountains] the way an ink landscape is built up: pale far ranges first, then
 * the mountain itself, dark along its ridges and fading into mist toward its foot, with brush
 * texture on its faces and the things only it has. A port of the phone's InkPainter
 * (android/.../theme/ink/InkPainter.kt) to Skia, kept line for line so the two paint alike; only
 * what the American scenes use is here.
 */
internal object InkPainter {
    /** The painting in two depths, and its seal and inscription. */
    class Scene(val back: Image, val front: Image, val seal: Image, val sealOnLeft: Boolean)

    /** How far in from the painting's edge the seal sits, as on the phone. */
    const val SEAL_INSET = 0.07f

    /** The painting's size in pixels, as on the phone. */
    const val WIDTH = 720
    const val HEIGHT = 960

    private const val SAMPLES = 480

    /** Painted ink to lend its brushwork to the mountains' faces; see [faces]. */
    @Volatile
    private var texture: Image? = null

    /** The faces the seal and its inscription are written in; the platform's serif when null. */
    class Faces(val seal: Typeface?, val motto: Typeface?)

    fun render(mountain: InkMountain, dark: Boolean, brush: Image?, faces: Faces, width: Int = WIDTH, height: Int = HEIGHT): Scene {
        val palette = Palette(dark)
        texture = brush
        val w = width.toFloat()
        val h = height.toFloat()
        val back = layer(width, height) { canvas ->
            mountain.far.forEachIndexed { index, range -> drawRange(canvas, range, width, height, palette, far = true, depth = index) }
            mountain.landmarks.filter { it.far }.forEach { InkLandmarks.draw(canvas, it, w, h, palette) }
            mountain.water?.let { drawWater(canvas, it, w, h, palette, mountain.name.hashCode()) }
        }
        val front = layer(width, height) { canvas ->
            var last: FloatArray? = null
            mountain.near.forEach { range -> last = drawRange(canvas, range, width, height, palette, far = false, depth = 0) }
            mountain.landmarks.filter { !it.far }.forEach { InkLandmarks.draw(canvas, it, w, h, palette) }
            mountain.foreground.forEach { range -> drawRange(canvas, range, width, height, palette, far = false, depth = 0) }
            val ridge = last
            val main = mountain.near.lastOrNull()
            if (ridge != null && main != null) {
                if (Feature.SheerFace in mountain.features) drawSheerFace(canvas, main, ridge, w, h, palette)
            }
        }
        return Scene(back, front, drawSeal(mountain, width, palette, faces), mountain.sealOnLeft)
    }

    /**
     * The whole painting on its paper, the way the phone shows it settled: far ranges, the
     * mountain, and the seal near the top on the open side of the sky.
     */
    fun compose(scene: Scene, dark: Boolean, width: Int = WIDTH, height: Int = HEIGHT, sealInset: Float = SEAL_INSET): Image = layer(width, height) { canvas ->
        val palette = Palette(dark)
        canvas.clear(argb(1f, palette.paper))
        canvas.place(scene.back, Rect.makeWH(width.toFloat(), height.toFloat()))
        canvas.place(scene.front, Rect.makeWH(width.toFloat(), height.toFloat()))
        val scale = width / WIDTH.toFloat()
        val sealWidth = scene.seal.width * scale
        val left = if (scene.sealOnLeft) width * sealInset else width * (1f - sealInset) - sealWidth
        canvas.place(scene.seal, Rect.makeXYWH(left, height * 0.11f, sealWidth, scene.seal.height * scale))
    }

    private fun Canvas.place(image: Image, into: Rect) {
        drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), into, SamplingMode.LINEAR, null, true)
    }

    private fun layer(width: Int, height: Int, draw: (Canvas) -> Unit): Image {
        val surface = Surface.makeRasterN32Premul(width, height)
        draw(surface.canvas)
        return surface.makeImageSnapshot().also { surface.close() }
    }

    /** Ink and paper for a light or a dark page; on a dark page the ink is moonlit silver. */
    class Palette(val dark: Boolean) {
        /** Cloud: the paper itself on a light page; on a dark one, a faint silver haze over the ink. */
        fun mist(alpha: Float) = if (dark) argb(alpha * 0.20f, 0x9AA6AB) else argb(alpha, paper)

        val ink = if (dark) 0xD8E2DE else 0x1C2622
        val farInk = if (dark) 0xB8C6CC else 0x36464F
        val paper = if (dark) 0x181A1F else 0xF0F2F5
        val seal = if (dark) 0xC9533F else 0xB3362A
        val strength = if (dark) 0.62f else 1f

        fun ink(alpha: Float, far: Boolean = false) = argb(alpha * strength, if (far) farInk else ink)
        fun paper(alpha: Float) = argb(alpha, paper)

        /** What holds the light, snow, falling water, a flame: bare paper, or moonlit on a dark page. */
        fun bright(alpha: Float) = if (dark) argb(alpha * 0.6f, 0xE8EEF0) else argb(alpha, paper)

        /** The shade on something bright: ink on a light page, the night itself on a dark one. */
        fun deep(alpha: Float) = if (dark) argb(alpha, paper) else argb(alpha, ink)
    }

    fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    private fun x(i: Int) = -0.04f + 1.08f * i / SAMPLES

    /** The ridge's height at every sample, as y measured from the top (0..1). */
    fun ridge(range: Range): FloatArray {
        val peaks = withShoulders(range)
        return FloatArray(SAMPLES + 1) { i ->
            val x = x(i)
            var h = 0f
            for (peak in peaks) {
                val half = if (x < peak.x) peak.left else peak.right
                val d = abs(x - peak.x) / half
                if (d < 1f) h = max(h, peak.height * (1f - d.pow(peak.round)).pow(peak.power))
            }
            if (h > 0f) {
                // Rock is never smooth: coarse undulation, notches and a fine jag, stronger higher up.
                h += range.roughness * fbm(x * 9f, range.seed, 5) * (0.35f + 1.4f * h)
                h += range.roughness * 1.1f * fbm(x * 23f, range.seed + 5, 4) * h
                h += range.roughness * 0.6f * fbm(x * 71f, range.seed + 7, 3) * h
            }
            range.base - max(h, 0f)
        }
    }

    /**
     * A mountain is never one peak: each summit gets lesser peaks and shoulders down its flanks,
     * placed by the range's seed, so the silhouette steps down the way real massifs do.
     */
    private fun withShoulders(range: Range): List<Peak> {
        if (!range.shoulders) return range.peaks
        val random = Random(range.seed * 31 + 7)
        val extra = mutableListOf<Peak>()
        for (peak in range.peaks) {
            if (peak.height < 0.18f || peak.round >= 4f) continue
            repeat(2 + random.nextInt(2)) {
                val side = if (random.nextBoolean()) 1f else -1f
                val half = if (side > 0) peak.right else peak.left
                val along = 0.25f + 0.5f * random.nextFloat()
                val x = peak.x + side * half * along
                val local = peak.height * (1f - along.pow(peak.round)).pow(peak.power)
                val height = local * (1.04f + 0.14f * random.nextFloat())
                val width = half * (0.18f + 0.22f * random.nextFloat())
                extra += Peak(x, height, width, width * (0.8f + 0.4f * random.nextFloat()), peak.power.coerceAtLeast(1.1f), peak.round)
            }
        }
        return range.peaks + extra
    }

    private fun drawRange(canvas: Canvas, range: Range, width: Int, height: Int, palette: Palette, far: Boolean, depth: Int): FloatArray {
        val ys = ridge(range)
        val w = width.toFloat()
        val h = height.toFloat()
        val bottom = (range.base + 0.14f) * h
        val body = path {
            moveTo(x(0) * w, bottom)
            for (i in 0..SAMPLES) lineTo(x(i) * w, ys[i] * h)
            lineTo(x(SAMPLES) * w, bottom)
            closePath()
        }
        val top = ys.min() * h
        val foot = range.base * h
        if (range.snow > 0f && !palette.dark) skyWash(canvas, range, ys, w, h, palette, far)
        val fill = paint {
            shader = if (range.body <= 0f) {
                linear(
                    0f, top, 0f, foot,
                    intArrayOf(palette.ink(range.ink * 0.62f, far), palette.ink(range.ink * 0.40f, far), palette.ink(range.ink * 0.14f, far), palette.ink(0f, far)),
                    floatArrayOf(0f, 0.36f, 0.74f, 1f),
                )
            } else {
                // Ink that stays to the foot still thins out below it, instead of ending on a line.
                val end = foot + (bottom - foot) * 0.6f
                val k = (foot - top) / (end - top)
                linear(
                    0f, top, 0f, end,
                    intArrayOf(
                        palette.ink(range.ink * 0.62f, far), palette.ink(range.ink * (0.40f + 0.12f * range.body), far),
                        palette.ink(range.ink * (0.14f + 0.28f * range.body), far), palette.ink(range.ink * 0.22f * range.body, far), palette.ink(0f, far),
                    ),
                    floatArrayOf(0f, 0.36f * k, 0.74f * k, k, 1f),
                )
            }
            if (range.blur > 0f) maskFilter = blur(range.blur * w)
        }
        canvas.drawPath(body, fill)

        val random = Random(range.seed)
        canvas.save()
        canvas.clip(body)
        // Brushwork thins out toward the foot as the ink does, instead of hanging below it.
        val layer = canvas.saveLayer(0f, top - 4f, w, bottom, null)
        shade(canvas, range, ys, w, h, palette, far)
        if (!far) faces(canvas, range, ys, w, h, palette)
        when (range.texture) {
            Texture.Hemp -> hemp(canvas, range, ys, w, h, palette, far, random)
            Texture.Strata -> strata(canvas, range, ys, w, h, palette, far, random)
            Texture.Dots -> dots(canvas, range, ys, w, h, palette, far, random)
            Texture.None -> Unit
        }
        canvas.drawRect(0f, top - 4f, w, bottom, plain {
            blendMode = BlendMode.DST_IN
            shader = linear(0f, top, 0f, foot - (foot - top) * 0.08f, intArrayOf(0xFF000000.toInt(), 0xCC000000.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 1f))
        })
        canvas.restoreToCount(layer)
        if (range.snow > 0f) snow(canvas, range, ys, w, h, palette, far)
        canvas.restore()
        if (!far || depth > 0) ridgeLine(canvas, range, ys, w, h, palette, far)
        return ys
    }

    /**
     * contrast wash: on a light page the sky just behind a snow peak is washed grey, the way a painter
     * makes white snow stand out from white paper. On a dark page the night does that already.
     */
    private fun skyWash(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean) {
        val top = ys.min()
        val reach = top + (range.base - top) * (range.snow + 0.1f)
        val first = ys.indexOfFirst { it < reach }
        val last = ys.indexOfLast { it < reach }
        if (first < 0 || last <= first) return
        val band = path {
            moveTo(x(first) * w, ys[first] * h)
            for (i in first..last) lineTo(x(i) * w, ys[i] * h)
            for (i in last downTo first) lineTo(x(i) * w, (ys[i] - 0.07f) * h)
            closePath()
        }
        canvas.drawPath(band, paint {
            color = palette.ink(if (far) 0.08f else 0.15f, far)
            maskFilter = blur(w * 0.07f)
        })
    }

    /**
     * Snow is the paper left bare: above the snow line the mountain is wiped back to paper (on a
     * dark page it holds the moonlight), its lower edge torn where glaciers run down the gullies,
     * the side turned from the light washed grey, and dark rock ribs coming up through it.
     */
    private fun snow(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean) {
        val top = ys.min()
        val span = (range.base - top).coerceAtLeast(0.01f)
        val summit = x(ys.indexOfFirst { it == top })
        val line = FloatArray(SAMPLES + 1) { i ->
            val wander = fbm(x(i) * 5f, range.seed + 29, 3) * 0.06f + fbm(x(i) * 60f, range.seed + 33, 2) * 0.02f
            val tongue = max(0f, fbm(x(i) * 24f, range.seed + 31, 3) - 0.1f).pow(1.6f) * 0.45f
            min(range.base, top + span * (range.snow + wander + tongue))
        }
        val cap = path {
            moveTo(x(0) * w, ys[0] * h)
            for (i in 1..SAMPLES) lineTo(x(i) * w, ys[i] * h)
            for (i in SAMPLES downTo 0) lineTo(x(i) * w, max(line[i], ys[i]) * h)
            closePath()
        }
        // The cap is whitest high up; down its tongues the snow thins and the rock shows through.
        val lowest = line.max()
        val reach = top + span * range.snow
        val edge = blur(max(0.6f, w * (if (palette.dark) 0.005f else 0.0025f)))
        // Paper first, so the rock's brushwork under the snow does not show through it on a dark page.
        canvas.drawPath(cap, paint {
            shader = linear(0f, reach * h - span * h * 0.05f, 0f, lowest * h, palette.paper(0.92f), palette.paper(0.2f))
            maskFilter = edge
        })
        canvas.drawPath(cap, paint {
            shader = linear(0f, reach * h - span * h * 0.05f, 0f, lowest * h, palette.bright(if (far) 0.85f else 0.97f), palette.bright(0.25f))
            maskFilter = edge
        })
        canvas.save()
        canvas.clip(cap)
        // The side turned from the light lies in a soft grey shade, deepening away from the summit.
        canvas.drawRect(0f, top * h, w, range.base * h, plain {
            shader = linear(summit * w - w * 0.04f, 0f, summit * w + w * 0.38f, 0f, palette.deep(0f), palette.deep(if (far) 0.08f else 0.12f))
        })
        val random = Random(range.seed + 41)
        val snowy = (0..SAMPLES).filter { line[it] - ys[it] > span * 0.06f }
        if (snowy.isNotEmpty()) {
            // Runnels of shade down the fall line, fanning out from the summit, more on the shaded side.
            val flute = paint { maskFilter = blur(w * 0.006f) }
            repeat(if (far) 10 else 34) {
                val i = snowy[random.nextInt(snowy.size)]
                val depth = (line[i] - ys[i]) * h
                val outward = ((x(i) - summit) / 0.4f).coerceIn(-1f, 1f)
                val x0 = x(i) * w
                val y0 = ys[i] * h + depth * 0.05f * random.nextFloat()
                val length = depth * (0.5f + 0.5f * random.nextFloat())
                val x1 = x0 + outward * length * 0.55f
                flute.color = palette.deep((0.05f + 0.07f * random.nextFloat()) * (if (x(i) > summit) 1f else 0.45f))
                taper(canvas, x0, y0, (x0 + x1) / 2f, y0 + length / 2f, x1, y0 + length, w * (0.012f + 0.012f * random.nextFloat()), flute)
            }
            // Rock ribs: dark and broken, pressed in near the top and lifted off toward the snow line.
            val rib = paint { maskFilter = blur(max(0.5f, w * 0.0012f)) }
            repeat(if (far) 4 else 8) {
                val i = snowy[random.nextInt(snowy.size)]
                val depth = (line[i] - ys[i]) * h
                val outward = ((x(i) - summit) / 0.4f).coerceIn(-1f, 1f)
                var x0 = x(i) * w
                var y0 = ys[i] * h + depth * 0.02f
                val length = depth * (0.55f + 0.4f * random.nextFloat())
                val width = w * (0.0028f + 0.0026f * random.nextFloat())
                rib.color = palette.deep((if (far) 0.25f else 0.38f) + 0.3f * random.nextFloat())
                val pieces = 1 + random.nextInt(2)
                for (piece in 0 until pieces) {
                    val part = length / pieces * (0.6f + 0.35f * random.nextFloat())
                    val x1 = x0 + outward * part * 0.55f
                    val y1 = y0 + part
                    taper(canvas, x0, y0, (x0 + x1) / 2f + (random.nextFloat() - 0.5f) * w * 0.014f, (y0 + y1) / 2f, x1, y1, width * (1f - piece * 0.25f) * (0.7f + 0.6f * random.nextFloat()), rib)
                    x0 = x1 + outward * part * 0.08f
                    y0 = y1 + part * 0.15f
                }
            }
        }
        canvas.restore()
    }

    /** A stroke pressed down at its start and lifted off toward its end, along a curve. */
    fun taper(canvas: Canvas, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, width: Float, paint: org.jetbrains.skia.Paint) {
        val steps = 14
        val left = FloatArray((steps + 1) * 2)
        val right = FloatArray((steps + 1) * 2)
        for (k in 0..steps) {
            val t = k / steps.toFloat()
            val px = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * cx + t * t * x1
            val py = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * cy + t * t * y1
            val tx = 2 * (1 - t) * (cx - x0) + 2 * t * (x1 - cx)
            val ty = 2 * (1 - t) * (cy - y0) + 2 * t * (y1 - cy)
            val length = kotlin.math.hypot(tx, ty).coerceAtLeast(0.001f)
            val half = width / 2f * (if (t < 0.12f) 0.65f + t / 0.12f * 0.35f else (1f - (t - 0.12f) / 0.88f).pow(0.8f))
            left[k * 2] = px - ty / length * half
            left[k * 2 + 1] = py + tx / length * half
            right[k * 2] = px + ty / length * half
            right[k * 2 + 1] = py - tx / length * half
        }
        canvas.drawPath(path {
            moveTo(left[0], left[1])
            for (k in 1..steps) lineTo(left[k * 2], left[k * 2 + 1])
            for (k in steps downTo 0) lineTo(right[k * 2], right[k * 2 + 1])
            closePath()
        }, paint)
    }

    /**
     * Something built, in ink: its outline filled darkest high up and paler toward its foot, the
     * page's brushwork laid into it, and a fine wet line round its edge, the way ruled architectural painting rules it.
     */
    fun inkShape(
        canvas: Canvas, shape: Path, top: Float, foot: Float, ink: Float, palette: Palette, far: Boolean, blur: Float,
        outline: Float, textured: Boolean = true, fadeTo: Float = 0.45f, seed: Int = 7,
    ) {
        canvas.drawPath(shape, paint {
            shader = linear(
                0f, top, 0f, foot,
                intArrayOf(palette.ink(ink, far), palette.ink(ink * (0.6f + 0.4f * fadeTo), far), palette.ink(ink * fadeTo, far)),
                floatArrayOf(0f, 0.6f, 1f),
            )
            if (blur > 0f) maskFilter = blur(blur)
        })
        if (textured) grain(canvas, shape, ink, palette, far, seed)
        if (outline > 0f) {
            canvas.drawPath(shape, paint {
                stroke(outline)
                color = palette.ink(min(1f, ink * 1.05f), far)
                maskFilter = blur(max(0.4f, outline * 0.5f))
            })
        }
    }

    /** Weathering on something built: fine vertical streaks of darker and paler ink, broken along their length. */
    fun grain(canvas: Canvas, shape: Path, ink: Float, palette: Palette, far: Boolean, seed: Int) {
        val bounds = shape.bounds
        val random = Random(seed)
        val streak = paint { strokeWidth = 2.2f; maskFilter = blur(0.9f) }
        canvas.save()
        canvas.clip(shape)
        var x = bounds.left
        var i = 0
        while (x < bounds.right) {
            val n = (fbm(i * 0.19f, seed + 3, 3) + 1f) / 2f
            val y0 = bounds.top + random.nextFloat() * bounds.height * 0.6f
            val y1 = y0 + bounds.height * (0.15f + 0.55f * random.nextFloat())
            streak.color = if (n > 0.6f) palette.paper(0.08f + 0.22f * (n - 0.6f) / 0.4f) else palette.ink(ink * 0.16f * (0.6f - n) / 0.6f, far)
            canvas.drawLine(x, y0, x, y1, streak)
            x += 1.7f
            i++
        }
        canvas.restore()
    }

    /** Light from the upper left on something built: its left side wiped paler, its right in shade. */
    fun sideLight(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, ink: Float, palette: Palette) {
        canvas.drawRect(left, top, right, bottom, plain {
            shader = linear(
                left, 0f, right, 0f,
                intArrayOf(palette.paper(0.30f), palette.paper(0f), palette.ink(0f), palette.ink(ink * 0.32f)),
                floatArrayOf(0f, 0.42f, 0.55f, 1f),
            )
        })
    }

    /** A bank of mist: a soft round cloud in the paper's colour, flattened. */
    fun mistBank(canvas: Canvas, cx: Float, cy: Float, radius: Float, squash: Float, palette: Palette, strength: Float = 0.92f) {
        val fill = paint {
            shader = radial(cx, cy, radius, intArrayOf(palette.mist(strength), palette.mist(strength * 0.6f), palette.mist(0f)), floatArrayOf(0f, 0.55f, 1f))
        }
        canvas.save()
        canvas.scaleAbout(1f, squash, cx, cy)
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.restore()
    }

    /**
     * Still water, as ink leaves it: the paper almost bare, mist along the far shore so what stands
     * there rises from the water, and broken ripple lines, fine and close far off, longer near us.
     */
    private fun drawWater(canvas: Canvas, level: Float, w: Float, h: Float, palette: Palette, seed: Int) {
        val random = Random(seed + 3)
        canvas.drawRect(0f, (level - 0.035f) * h, w, (level + 0.03f) * h, plain {
            shader = linear(
                0f, (level - 0.035f) * h, 0f, (level + 0.03f) * h,
                intArrayOf(palette.mist(0f), palette.mist(0.9f), palette.mist(0.35f), palette.mist(0f)), floatArrayOf(0f, 0.5f, 0.75f, 1f),
            )
        })
        val ripplePaint = paint { maskFilter = blur(max(0.5f, w * 0.0008f)) }
        val rows = 22
        for (row in 0 until rows) {
            val t = (row + 1) / rows.toFloat()
            val y = (level + 0.014f + (1f - level - 0.014f) * t.pow(1.6f)) * h
            repeat(2 + random.nextInt(3)) {
                val x0 = (-0.1f + 1.1f * random.nextFloat()) * w
                val length = w * (0.04f + 0.22f * t) * (0.5f + random.nextFloat())
                ripplePaint.color = palette.ink((0.08f + 0.16f * t) * (0.6f + 0.4f * random.nextFloat()))
                ripple(canvas, x0, y + (random.nextFloat() - 0.5f) * h * 0.006f, length, h * 0.0030f * (0.5f + t), w * (0.0014f + 0.0022f * t), ripplePaint)
            }
        }
    }

    /** One ripple: a stroke that swells and thins along its length, rising and falling gently. */
    private fun ripple(canvas: Canvas, x0: Float, y: Float, length: Float, amplitude: Float, width: Float, paint: org.jetbrains.skia.Paint) {
        val steps = 24
        val seed = (x0 * 13f + y * 7f).toInt()
        val rise = FloatArray(steps + 1) { k -> amplitude * fbm(k / steps.toFloat() * length / 60f, seed, 2) }
        canvas.drawPath(path {
            for (k in 0..steps) {
                val t = k / steps.toFloat()
                val half = width / 2f * kotlin.math.sin(Math.PI * t).toFloat().pow(0.7f)
                if (k == 0) moveTo(x0 + length * t, y + rise[k] - half) else lineTo(x0 + length * t, y + rise[k] - half)
            }
            for (k in steps downTo 0) {
                val t = k / steps.toFloat()
                val half = width / 2f * kotlin.math.sin(Math.PI * t).toFloat().pow(0.7f)
                lineTo(x0 + length * t, y + rise[k] + half)
            }
            closePath()
        }, paint)
    }

    /**
     * Light from the upper left: faces turned away fall into shade, faces turned toward it stay
     * pale. Drawn as thin vertical slices whose ink also wavers with noise, so the rock reads as
     * striated stone rather than a smooth gradient.
     */
    private fun shade(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean) {
        val top = ys.min()
        val span = (range.base - top).coerceAtLeast(0.01f)
        val slice = w * 1.08f / SAMPLES + 0.6f
        val dark = paint { strokeWidth = slice * 1.6f; maskFilter = blur(max(1f, w * 0.0026f)) }
        for (i in 1 until SAMPLES) {
            val height = (range.base - ys[i]) / span
            if (height < 0.06f) continue
            val slope = (ys[i + 1] - ys[i - 1]) / (x(i + 1) - x(i - 1))
            val streak = (fbm(i * 0.37f, range.seed + 11, 3) + 1f) / 2f
            val shadow = (slope.coerceIn(-3f, 3f) / 3f + 0.25f).coerceIn(0f, 1f)
            val alpha = range.ink * (0.04f + 0.24f * shadow) * (0.55f + 0.6f * streak) * height
            if (alpha < 0.01f) continue
            dark.color = palette.ink(alpha, far)
            val length = span * h * (0.18f + 0.5f * streak) * (0.5f + 0.5f * height)
            canvas.drawLine(x(i) * w, ys[i] * h, x(i) * w, ys[i] * h + length, dark)
        }
        // Lit faces are wiped paler near the ridge, as a dry brush leaves paper showing.
        val light = paint { strokeWidth = slice; maskFilter = blur(max(0.6f, w * 0.0015f)) }
        for (i in 1 until SAMPLES step 2) {
            val height = (range.base - ys[i]) / span
            val slope = (ys[i + 1] - ys[i - 1]) / (x(i + 1) - x(i - 1))
            if (height < 0.15f || slope > -0.6f) continue
            val streak = (fbm(i * 0.53f, range.seed + 13, 2) + 1f) / 2f
            light.color = palette.paper(0.10f + 0.16f * streak)
            canvas.drawLine(x(i) * w, ys[i] * h + h * 0.012f, x(i) * w, ys[i] * h + span * h * 0.22f * streak, light)
        }
    }

    /**
     * The brushwork of a painted mountain laid into this one's faces: the painting's ink, in
     * this page's ink colour, fading out toward the foot. Each mountain takes a different part.
     */
    private fun faces(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette) {
        val source = texture ?: return
        val top = ys.min()
        val scale = (range.base - top) * h * 1.55f / source.height
        val matrix = Matrix33.makeTranslate(
            -source.width * scale * (0.18f + 0.5f * ((range.seed * 37) % 100) / 100f),
            top * h - source.height * scale * 0.18f,
        ).makeConcat(Matrix33.makeScale(scale, scale))
        val brush = source.makeShader(FilterTileMode.MIRROR, FilterTileMode.MIRROR, SamplingMode.LINEAR, matrix)
        val fade = linear(0f, top * h, 0f, range.base * h, intArrayOf(0xFF000000.toInt(), 0x99000000.toInt(), 0x00000000), floatArrayOf(0f, 0.5f, 1f))
        canvas.drawRect(0f, top * h, w, range.base * h, paint {
            shader = masked(brush, fade)
            colorFilter = ColorFilter.makeBlend(palette.ink(range.ink * 0.85f), BlendMode.SRC_IN)
        })
    }

    /** The wet edge of a brush along the ridge: dark at the summits, thinning where the ridge sinks. */
    private fun ridgeLine(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean) {
        val top = ys.min()
        val span = (range.base - top).coerceAtLeast(0.01f)
        val line = paint {
            stroke(1f)
            maskFilter = blur(max(0.6f, w * 0.0012f))
        }
        val chunk = 6
        // Over snow the outline is drawn lighter, so the white is not caged in black.
        val snowLine = if (range.snow > 0f) top + span * range.snow else -1f
        var i = 0
        while (i < SAMPLES) {
            val end = min(i + chunk, SAMPLES)
            val height = (range.base - ys[i]) / span
            // A dry brush skips: the line fades in and out along the ridge instead of tracing it evenly.
            val dry = ((fbm(i * 0.045f, range.seed + 9, 3) + 1f) / 2f).let { it * it * (3f - 2f * it) }
            if (height > 0.04f && dry > 0.18f) {
                val light = if (ys[i] < snowLine) 0.5f else 1f
                line.strokeWidth = w * (0.0012f + 0.0040f * height * dry * light) * (0.8f + 0.4f * (fbm(i * 0.11f, range.seed + 3, 2) + 1f) / 2f)
                line.color = palette.ink(range.ink * (0.22f + 0.66f * height) * dry * light, far)
                val from = i
                canvas.drawPath(path {
                    moveTo(x(from) * w, ys[from] * h)
                    for (k in from + 1..end) lineTo(x(k) * w, ys[k] * h)
                }, line)
            }
            i = end
        }
    }

    /** hemp-fiber strokes: long, soft strokes running down the slopes, dark and light, like hemp fibres. */
    private fun hemp(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean, random: Random) {
        val top = ys.min()
        val span = (range.base - top).coerceAtLeast(0.01f)
        val fibre = paint { stroke(1f); maskFilter = blur(max(0.5f, w * 0.0008f)) }
        repeat(if (far) 70 else 190) {
            val i = random.nextInt(SAMPLES)
            val height = (range.base - ys[i]) / span
            if (height < 0.12f) return@repeat
            val slope = (ys[min(i + 3, SAMPLES)] - ys[max(i - 3, 0)]) / (x(min(i + 3, SAMPLES)) - x(max(i - 3, 0)))
            val length = (0.04f + 0.13f * random.nextFloat()) * span * h * height
            val startX = x(i) * w
            val startY = ys[i] * h + random.nextFloat() * 0.02f * h
            val lean = (slope * 0.25f).coerceIn(-0.6f, 0.6f) * length + (random.nextFloat() - 0.5f) * 6f
            val stroke = path {
                moveTo(startX, startY)
                quadTo(startX + lean * 0.3f + (random.nextFloat() - 0.5f) * 5f, startY + length * 0.5f, startX + lean, startY + length)
            }
            val light = random.nextFloat() < 0.38f
            fibre.strokeWidth = w * (0.0010f + 0.0016f * random.nextFloat())
            fibre.color = if (light) palette.paper(0.20f + 0.12f * random.nextFloat()) else palette.ink(range.ink * (0.16f + 0.22f * random.nextFloat()) * height, far)
            canvas.drawPath(stroke, fibre)
        }
    }

    /** The beds of a layered massif, broken lines following the mountain's shape down its body. */
    private fun strata(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean, random: Random) {
        val top = ys.min()
        val span = (range.base - top).coerceAtLeast(0.01f)
        val bedPaint = paint { stroke(1f); maskFilter = blur(max(0.5f, w * 0.0008f)) }
        val beds = if (far) 3 + (range.body * 2).toInt() else 6
        for (bed in 1..beds) {
            val depth = bed / (beds + 1f) * span * 0.75f
            var i = 0
            while (i < SAMPLES) {
                val run = 5 + random.nextInt(16)
                val gap = 6 + random.nextInt(18)
                val end = min(i + run, SAMPLES)
                val height = (range.base - ys[i]) / span
                if (height > 0.18f) {
                    bedPaint.strokeWidth = w * (0.0012f + 0.0012f * random.nextFloat())
                    bedPaint.color = if (bed % 3 == 0) palette.paper(0.16f) else palette.ink(range.ink * 0.18f * height, far)
                    val from = i
                    canvas.drawPath(path {
                        moveTo(x(from) * w, (ys[from] + depth * (0.6f + 0.4f * height)) * h)
                        for (k in from + 1..end) lineTo(x(k) * w, (ys[k] + depth * (0.6f + 0.4f * (range.base - ys[k]) / span)) * h)
                    }, bedPaint)
                }
                i = end + gap
            }
        }
        hemp(canvas, range, ys, w, h, palette, far, random)
    }

    /** foliage stippling: clusters of dots for the trees on soft, wooded ridges. */
    private fun dots(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette, far: Boolean, random: Random) {
        val top = ys.min()
        val span = (range.base - top).coerceAtLeast(0.01f)
        val dot = paint { maskFilter = blur(max(0.5f, w * 0.0008f)) }
        repeat(if (far) 220 else 620) {
            val i = random.nextInt(SAMPLES)
            val height = (range.base - ys[i]) / span
            if (height < 0.10f) return@repeat
            val below = random.nextFloat().pow(2.2f) * 0.16f * h
            val radius = w * (0.0016f + 0.0034f * random.nextFloat()) * (0.6f + 0.6f * height)
            dot.color = palette.ink(range.ink * (0.25f + 0.45f * random.nextFloat()) * height, far)
            canvas.drawCircle(x(i) * w + (random.nextFloat() - 0.5f) * 6f, ys[i] * h + below, radius, dot)
        }
    }

    /**
     * Half Dome: the sheer face, turned a little toward us, dark with the streaks water leaves down
     * granite; above it the dome stays pale, its sheets of granite peeling away in curves.
     */
    private fun drawSheerFace(canvas: Canvas, range: Range, ys: FloatArray, w: Float, h: Float, palette: Palette) {
        val peak = range.peaks.maxBy { it.height }
        val summit = range.base - peak.height
        val edge = (peak.x - peak.left) * w
        val reach = w * 0.085f
        val foot = (summit + peak.height * 0.55f) * h
        val body = path {
            moveTo(x(0) * w, range.base * h)
            for (i in 0..SAMPLES) lineTo(x(i) * w, ys[i] * h)
            lineTo(x(SAMPLES) * w, range.base * h)
            closePath()
        }
        val random = Random(range.seed + 47)
        canvas.save()
        canvas.clip(body)
        canvas.drawRect(edge - 2f, summit * h, edge + reach, foot, plain {
            shader = masked(
                linear(edge, 0f, edge + reach, 0f, palette.ink(0.42f), palette.ink(0f)),
                linear(0f, summit * h, 0f, foot, 0xFF000000.toInt(), 0x00000000),
            )
        })
        val streak = paint { maskFilter = blur(max(0.5f, w * 0.0010f)) }
        repeat(30) {
            val x0 = edge + reach * random.nextFloat().pow(1.6f)
            val i = ((x0 / w + 0.04f) / 1.08f * SAMPLES).toInt().coerceIn(0, SAMPLES)
            val y0 = ys[i] * h + h * 0.004f + random.nextFloat() * (foot - ys[i] * h) * 0.2f
            val length = (foot - y0) * (0.3f + 0.7f * random.nextFloat())
            streak.color = palette.ink(0.25f + 0.35f * random.nextFloat() * (1f - (x0 - edge) / reach))
            taper(canvas, x0, y0, x0 + (random.nextFloat() - 0.5f) * w * 0.004f, y0 + length / 2f, x0 + (random.nextFloat() - 0.5f) * w * 0.006f, y0 + length, w * (0.002f + 0.004f * random.nextFloat()), streak)
        }
        // Granite sheets on the dome: pale curves following its crown down the far side.
        val sheet = paint { stroke(1f); maskFilter = blur(max(0.5f, w * 0.0012f)) }
        val from = ((peak.x + 0.03f + 0.04f) / 1.08f * SAMPLES).toInt()
        val to = ((peak.x + peak.right * 0.85f + 0.04f) / 1.08f * SAMPLES).toInt().coerceAtMost(SAMPLES)
        for (layer in 1..4) {
            val depth = peak.height * h * (0.05f + 0.06f * layer + 0.02f * random.nextFloat())
            var start = from + random.nextInt(16)
            val end = to - random.nextInt(30)
            // Each sheet in a few dry pieces, not one ruled line.
            while (start < end - 4) {
                val stop = min(end, start + 10 + random.nextInt(26))
                sheet.strokeWidth = w * (0.0014f + 0.0014f * random.nextFloat())
                sheet.color = if (random.nextFloat() < 0.5f) palette.ink(0.10f + 0.12f * random.nextFloat()) else palette.paper(0.18f + 0.18f * random.nextFloat())
                val first = start
                canvas.drawPath(path {
                    moveTo(x(first) * w, ys[first] * h + depth)
                    for (k in first + 1..stop) lineTo(x(k) * w, ys[k] * h + depth * (1f + 0.5f * (k - from) / (to - from).toFloat()))
                }, sheet)
                start = stop + 3 + random.nextInt(10)
            }
        }
        canvas.restore()
    }

    /**
     * The seal in vermilion with the mountain's name cut white into it, and beside it the motto
     * written down the page in pale ink, as an inscription sits in a painting's open sky.
     */
    private fun drawSeal(mountain: InkMountain, width: Int, palette: Palette, faces: Faces): Image {
        val w = width.toFloat()
        val glyph = w * 0.036f
        val motto = w * 0.030f
        val sealWidth = glyph * 1.55f
        val sealHeight = glyph * mountain.name.length * 1.12f + glyph * 0.5f
        val mottoHeight = motto * mountain.motto.length * 1.18f
        val bitmapWidth = (sealWidth + motto * 2.6f).toInt() + 4
        val bitmapHeight = (max(sealHeight, mottoHeight) + motto).toInt() + 4
        return layer(bitmapWidth, bitmapHeight) { canvas ->
            val sealLeft = if (mountain.sealOnLeft) 2f else bitmapWidth - sealWidth - 2f
            val mottoX = if (mountain.sealOnLeft) sealLeft + sealWidth + motto * 1.25f else sealLeft - motto * 1.25f
            // The motto runs down from the top; the seal sits below its first characters.
            val mottoFont = Font(faces.motto, motto)
            val ink = paint { color = palette.ink(0.62f) }
            mountain.motto.forEachIndexed { index, char -> canvas.drawCentred(char.toString(), mottoX, motto * (1f + index * 1.18f), mottoFont, ink) }
            val sealTop = motto * 0.8f
            val red = paint { color = argb(if (palette.dark) 0.86f else 0.92f, palette.seal) }
            canvas.drawRRect(RRect.makeXYWH(sealLeft, sealTop, sealWidth, sealHeight, glyph * 0.16f), red)
            val sealFont = Font(faces.seal, glyph)
            val white = paint { color = argb(0.96f, 0xF6F1E7) }
            mountain.name.forEachIndexed { index, char ->
                canvas.drawCentred(char.toString(), sealLeft + sealWidth / 2f, sealTop + glyph * 0.25f + glyph * (0.92f + index * 1.12f), sealFont, white)
            }
            // A carved seal is never clean: a few specks of paper show through the red.
            val random = Random(mountain.name.hashCode())
            val speck = paint { color = argb(0.55f, 0xF6F1E7); mode = PaintMode.FILL }
            repeat(14) {
                canvas.drawCircle(sealLeft + random.nextFloat() * sealWidth, sealTop + random.nextFloat() * sealHeight, glyph * 0.035f * (0.5f + random.nextFloat()), speck)
            }
        }
    }

    /** Paint.Align.CENTER on the phone: the text centred on x, its baseline on y. */
    private fun Canvas.drawCentred(text: String, x: Float, y: Float, font: Font, paint: org.jetbrains.skia.Paint) {
        drawString(text, x - font.measureTextWidth(text) / 2f, y, font, paint)
    }

    private fun hash(i: Int, seed: Int): Float {
        var x = i * 374761393 + seed * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xffff) / 32767.5f - 1f
    }

    private fun noise(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)
        return hash(i, seed) + (hash(i + 1, seed) - hash(i, seed)) * u
    }

    /** Fractal value noise in -1..1. */
    fun fbm(x: Float, seed: Int, octaves: Int): Float {
        var sum = 0f
        var amplitude = 0.5f
        var frequency = 1f
        var norm = 0f
        for (octave in 0 until octaves) {
            sum += amplitude * noise(x * frequency, seed + octave * 17)
            norm += amplitude
            amplitude *= 0.5f
            frequency *= 2.1f
        }
        return sum / norm
    }
}
