package cc.txwd.kone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cc.txwd.kone.city.CityScene
import cc.txwd.kone.city.Environment
import cc.txwd.kone.city.Polygon
import cc.txwd.kone.city.SceneMotion
import cc.txwd.kone.city.ScenePaintings
import cc.txwd.kone.city.SkyState
import cc.txwd.kone.city.TAU
import cc.txwd.kone.city.WaterRow
import cc.txwd.kone.city.WindowLight
import cc.txwd.kone.city.activity
import cc.txwd.kone.city.cloudPigment
import cc.txwd.kone.city.lightStrength
import cc.txwd.kone.city.reflectionX
import cc.txwd.kone.city.smooth
import cc.txwd.kone.city.unit
import cc.txwd.kone.city.wrap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Surface
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a scene's wallpaper lies in its panel: the panel shows the handscroll's cut of it, x
 * across w by h. Codex's sizes are in the CSS pixels of a phone about 300 wide, so [css] is one
 * of those here.
 */
internal class Cover(val x: Int, val w: Int, val h: Int, scene: CityScene) {
    val crop = scene.crop
    val top = -crop.top / crop.height * h
    val height = h / crop.height
    val css = w / 300.0

    fun px(fx: Double) = (x + fx * w).toFloat()
    fun py(fy: Double) = (top + fy * height).toFloat()
}

/**
 * A scene's painting as one panel shows it: the night and day paintings blended for the
 * daylight at the panel's size in pixels, so drawing it each frame is a plain copy; and the soft
 * edge along its banks. Made again only when the daylight or the size changes.
 */
class PanelPainting(val image: ImageBitmap, val banks: ImageBitmap?, private val key: String) {
    fun fits(w: Int, h: Int, daylight: Double) = key == key(w, h, daylight)

    companion object {
        private fun key(w: Int, h: Int, daylight: Double) = "${w}x$h@${(daylight * 1000).roundToInt()}"

        fun make(scene: CityScene, paintings: ScenePaintings, daylight: Double, w: Int, h: Int, banks: ImageBitmap?): PanelPainting {
            val image = ImageBitmap(w, h)
            val canvas = Canvas(image)
            val paint = Paint().apply { filterQuality = FilterQuality.High }
            for ((painting, alpha) in listOf(paintings.night to 1f, paintings.day to daylight.toFloat())) {
                if (alpha <= 0f || (painting === paintings.night && daylight >= 1)) continue
                paint.alpha = alpha
                canvas.drawImageRect(painting, IntOffset.Zero, IntSize(painting.width, painting.height), IntOffset.Zero, IntSize(w, h), paint)
            }
            return PanelPainting(image, banks ?: banks(scene, w, h), key(w, h, daylight))
        }

        /** The banks of the water, stroked and blurred: what the swaying rows fade into along the shore. */
        private fun banks(scene: CityScene, w: Int, h: Int): ImageBitmap? {
            val motion = SceneMotion.of(scene)
            if (motion.water.isEmpty()) return null
            val cover = Cover(0, w, h, scene)
            val surface = Surface.makeRasterN32Premul(w, h)
            org.jetbrains.skia.Paint().use { paint ->
                paint.mode = PaintMode.STROKE
                paint.strokeWidth = (2.2 * cover.css).toFloat()
                paint.color = 0xB8000000.toInt()
                paint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, (1.1 * cover.css).toFloat())
                (motion.water + motion.waterExclusions).forEach { surface.canvas.drawPath(it.path(cover).asSkiaPath(), paint) }
            }
            return surface.makeImageSnapshot().toComposeImageBitmap().also { surface.close() }
        }
    }
}

/**
 * Codex's motion layer over a city painting: the water redrawn in rows that sway, glints on it,
 * clouds of porous pigment drifting with the wind, mist breathing, and lit windows that change
 * now and then. All of it on a layer of its own over the painting, under the sun and moon.
 */
fun DrawScope.drawMotion(
    scene: CityScene, painting: PanelPainting, lights: List<WindowLight>, sky: SkyState, environment: Environment, seconds: Double, x: Int, w: Int, h: Int,
) {
    val motion = SceneMotion.of(scene)
    val cover = Cover(x, w, h, scene)
    val daylight = (sky.daylight * 1000).roundToInt() / 1000.0
    drawContext.canvas.saveLayer(Rect(x.toFloat(), 0f, (x + w).toFloat(), h.toFloat()), Paint())
    drawWater(scene, motion, painting, sky, daylight, environment, seconds, cover)
    drawClouds(motion, daylight, environment, seconds, cover)
    drawMist(motion, daylight, environment, seconds, cover)
    drawLights(motion, lights, daylight, seconds, cover)
    drawContext.canvas.restore()
}

internal fun Polygon.path(cover: Cover) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(cover.px(p.x), cover.py(p.y)) else lineTo(cover.px(p.x), cover.py(p.y)) }
    close()
}

/** Clips to [inside] less every one of [outside], for [block]. */
internal fun DrawScope.clipped(inside: Path?, outside: List<Path>, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.save()
    inside?.let { canvas.clipPath(it) }
    outside.forEach { canvas.clipPath(it, ClipOp.Difference) }
    block()
    canvas.restore()
}

/** The rows of each water, worked out once for each size the panel takes. */
private val waterRows = object : LinkedHashMap<String, List<List<WaterRow>>>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<List<WaterRow>>>) = size > 24
}

private fun DrawScope.drawWater(
    scene: CityScene, motion: SceneMotion, painting: PanelPainting, sky: SkyState, daylight: Double,
    environment: Environment, seconds: Double, cover: Cover,
) {
    if (motion.water.isEmpty()) return
    val image = painting.image
    val rows = synchronized(waterRows) {
        waterRows.getOrPut("${scene.id}:${cover.w}x${cover.h}") {
            // Rows a CSS pixel and a half high, as Codex's; a pixel in from every bank.
            val rowHeight = maxOf(2, (1.5 * cover.css).roundToInt()) / cover.height
            val shown = -cover.top / cover.height
            motion.water.indices.map { motion.waterRows(it, rowHeight, 1.0 / cover.w, 1 / cover.height, shown, shown + cover.h / cover.height) }
        }
    }
    val exclusions = motion.waterExclusions.map { it.path(cover) }
    val amplitude = 6 * motion.waterEnergy * cover.css
    val glow = lightStrength(sky)
    val bodyX = reflectionX(sky)
    motion.water.forEachIndexed { index, polygon ->
        clipped(polygon.path(cover), exclusions) {
            for (row in rows[index]) {
                val depth = unit((row.middle - polygon.top) / maxOf(.001, polygon.bottom - polygon.top))
                val advected = environment.advection * motion.waterPace
                // Several wavelengths that travel together, calmer in the distance than near by.
                val group = (.72 + .18 * environment.waveGroup + .1 * sin(seconds * .16 + row.middle * 38 + index)).coerceIn(.5, 1.15)
                val windGain = (.55 + .35 * environment.wind + .1 * environment.waveGroup).coerceIn(.55, 1.25)
                val waves = .57 * sin(advected * 1.65 + row.middle * 190 + index * 1.7) +
                    .28 * sin(advected * .69 - row.middle * 337 + index * .73) +
                    .15 * sin(advected * 2.73 + row.middle * 743 + sin(seconds * .13 + index) * .4)
                val dx = (amplitude * (waves * group * windGain * (.38 + .62 * depth)).coerceIn(-1.0, 1.0)).roundToInt()
                val top = cover.py(row.top).roundToInt().coerceAtLeast(0)
                val bottom = cover.py(row.bottom).roundToInt().coerceAtMost(cover.h)
                if (bottom <= top) continue
                for ((left, right) in row.spans) {
                    val from = (left * cover.w).roundToInt().coerceAtLeast(0)
                    val to = (right * cover.w).roundToInt().coerceAtMost(cover.w)
                    if (to <= from) continue
                    drawImage(
                        image,
                        srcOffset = IntOffset(from, top), srcSize = IntSize(to - from, bottom - top),
                        dstOffset = IntOffset(cover.x + from + dx, top), dstSize = IntSize(to - from, bottom - top),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            drawGlints(motion, index, polygon, daylight, glow, bodyX, environment, seconds, cover)
        }
    }
    // A soft edge along the banks, so the swaying rows never show the outline's hard line.
    painting.banks?.let { drawImage(it, topLeft = Offset(cover.x.toFloat(), 0f), blendMode = BlendMode.DstOut) }
}

private fun DrawScope.drawGlints(
    motion: SceneMotion, index: Int, box: Polygon, daylight: Double, glow: Double, bodyX: Double?,
    environment: Environment, seconds: Double, cover: Cover,
) {
    val span = box.right - box.left
    val depth = box.bottom - box.top
    val color = Color((213 + daylight * 29).roundToInt(), (197 + daylight * 38).roundToInt(), (157 + daylight * 52).roundToInt())
    val stroke = Stroke(width = (.75 * cover.css).toFloat())
    val path = Path()
    motion.glints[index].forEachIndexed { i, g ->
        val drift = wrap(environment.advection * motion.waterPace * .014 * (.45 + .55 * g.speed) + g.x, 1.0)
        val nx = box.left + drift * span
        val surfaceY = (g.y + .008 * sin(environment.advection * .7 + g.phase)).coerceIn(.015, .985)
        val ny = box.top + surfaceY * depth
        val crest = .5 + .5 * sin(ny * 260 - environment.advection * 1.3 + index * 1.7)
        val capillary = .5 + .5 * sin(seconds * TAU / g.period + g.phase)
        val pulse = .25 + .75 * (.64 * crest + .36 * capillary)
        val reflection = if (bodyX == null) 0.0 else exp(-((nx - bodyX) / .19).pow(2)) * environment.directVisibility
        val alpha = .245 * pulse * (.4 + .6 * glow) * (.6 + .4 * reflection) * (.28 + .72 * environment.fogVisibility) *
            environment.shimmer.coerceIn(.35, 1.5) * (1 - environment.cloudCover * .25) * (.15 + .85 * motion.waterEnergy)
        val length = (minOf(span * .3, g.length) * cover.w * (.55 + surfaceY * .8) * (.88 + .12 * sin(environment.advection * .65 + g.phase))).toFloat()
        val x = cover.px(nx)
        val y = cover.py(ny)
        path.reset()
        path.moveTo(x - length / 2, y)
        path.quadraticTo(x, y + (sin(seconds * .9 + i) * .65 * cover.css).toFloat(), x + length / 2, y)
        drawPath(path, color.copy(alpha = unit(alpha).toFloat()), style = stroke, blendMode = BlendMode.Screen)
    }
}

/** Codex's six cloud pigments, day and night, made once. */
private val pigments = HashMap<Pair<Int, Boolean>, ImageBitmap>()

private fun pigment(seed: Int, night: Boolean): ImageBitmap = synchronized(pigments) {
    pigments.getOrPut(seed to night) {
        Image.makeRaster(ImageInfo(192, 96, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), cloudPigment(seed, night), 192 * 4).toComposeImageBitmap()
    }
}

private fun DrawScope.drawClouds(motion: SceneMotion, daylight: Double, environment: Environment, seconds: Double, cover: Cover) {
    val (bandTop, bandBottom) = motion.cloudBand
    if (bandBottom <= bandTop) return
    val band = bandBottom - bandTop
    val sky = Path().apply { addRect(Rect(cover.x.toFloat(), cover.py(bandTop), (cover.x + cover.w).toFloat(), cover.py(bandBottom))) }
    clipped(sky, motion.cloudExclusions.map { it.path(cover) }) {
        val opacity = (.6 + .33 * daylight) * (1 + environment.rain * .35)
        motion.cloudBanks.forEachIndexed { index, bank ->
            val breathe = 1 + .018 * sin(environment.advection * .018 + bank.phase)
            val cloudW = cover.w * bank.width * breathe
            val cloudH = cover.height * band * bank.height / breathe
            // Far banks drift slowly, near ones faster; the wind carries them, gusts never jump them.
            val period = cover.w * 1.4 + cloudW
            val speed = 8.8 * cover.css * (.48 + bank.depth * .7) * bank.speed
            val left = wrap(bank.offset * cover.w + cloudW + environment.advection * speed, period) - cloudW
            val top = cover.top + (bandTop + band * bank.elevation) * cover.height + sin(seconds * .026 + bank.phase) * 2.4 * cover.css
            val density = opacity * (.45 + bank.depth * .38) * (.65 + environment.cloudCover * .55) * (1 + environment.gust * .035)
            val morph = smooth(.5 + .5 * sin(seconds * (.009 + index * .0017) + bank.phase))
            val size = IntSize(cloudW.roundToInt(), cloudH.roundToInt())
            for ((layer, which) in listOf(0 to index, 1 to (index + 1) % motion.pigments.size)) {
                val shape = density * (if (layer == 1) morph else 1 - morph)
                for ((night, weight) in listOf(false to daylight, true to 1 - daylight)) {
                    if (weight <= 0) continue
                    val image = pigment(motion.pigments[which], night)
                    for (offset in listOf(-period, 0.0, period)) {
                        val at = left + offset
                        if (at + cloudW < 0 || at > cover.w) continue
                        withTransform({ translate((cover.x + at).toFloat(), top.toFloat()) }) {
                            drawImage(image, dstSize = size, alpha = unit(shape * weight).toFloat(), filterQuality = FilterQuality.Medium)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawMist(motion: SceneMotion, daylight: Double, environment: Environment, seconds: Double, cover: Cover) {
    motion.mist.forEachIndexed { index, polygon ->
        val centerX = cover.px((polygon.left + polygon.right) / 2)
        val centerY = cover.py((polygon.top + polygon.bottom) / 2)
        val rx = maxOf(1.0, (polygon.right - polygon.left) * cover.w * .65).toFloat()
        val ry = maxOf(1.0, (polygon.bottom - polygon.top) * cover.height * .55).toFloat()
        val drift = (sin(environment.advection * TAU / 29 + index * 2) * 28 * cover.css * (.8 + environment.wind * .2)).toFloat()
        val breathe = .72 + .28 * sin(seconds * TAU / 23 + index)
        val opacity = ((.026 + daylight * .014) * breathe * 2.5).toFloat()
        clipped(polygon.path(cover), emptyList()) {
            withTransform({
                translate(centerX + drift, centerY)
                scale(rx, ry, pivot = Offset.Zero)
            }) {
                drawRect(
                    Brush.radialGradient(0f to MIST.copy(alpha = opacity), 1f to MIST.copy(alpha = 0f), center = Offset.Zero, radius = 1f),
                    topLeft = Offset(-1f, -1f), size = Size(2f, 2f),
                )
            }
        }
    }
}

private val MIST = Color(221, 227, 224)

private fun DrawScope.drawLights(motion: SceneMotion, lights: List<WindowLight>, daylight: Double, seconds: Double, cover: Cover) {
    val darkness = smooth(1 - daylight)
    if (darkness <= .001 || lights.isEmpty()) return
    val radius = (1.875 * cover.css).toFloat()
    lights.groupBy { it.region }.forEach { (region, inRegion) ->
        clipped(motion.lightRegions[region].path(cover), emptyList()) {
            for (light in lights) {
                val alpha = ((.035 + .34 * light.activity(seconds)) * darkness).toFloat()
                val color = Color(light.r, light.g, light.b)
                val center = Offset(cover.px(light.x), cover.py(light.y))
                drawCircle(
                    Brush.radialGradient(0f to color.copy(alpha = alpha), .4f to color.copy(alpha = alpha * .52f), 1f to color.copy(alpha = 0f), center = center, radius = radius),
                    radius = radius, center = center, blendMode = BlendMode.Screen,
                )
            }
        }
    }
}
