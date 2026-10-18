package cc.txwd.kone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cc.txwd.kone.city.CityScene
import cc.txwd.kone.city.Environment
import cc.txwd.kone.city.Precipitation
import cc.txwd.kone.city.SceneMotion
import cc.txwd.kone.city.ScenePaintings
import cc.txwd.kone.city.Seeds
import cc.txwd.kone.city.SkyState
import cc.txwd.kone.city.Sunlight
import cc.txwd.kone.city.WeatherProfile
import cc.txwd.kone.city.moonReflection
import cc.txwd.kone.city.smooth
import cc.txwd.kone.city.unit
import cc.txwd.kone.city.wrap
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/*
 * Sunlight and weather over a city painting: Codex's lighting layer (lighting-v13.js) and weather
 * layer (weather-v13.js). Sizes there are the CSS pixels of a phone wallpaper about 300 wide;
 * here [Cover.css] is one of them, and the wallpaper's width and height are the panel's width
 * and [Cover.height].
 */

private fun rgba(r: Int, g: Int, b: Int, alpha: Double) = Color(r, g, b, (unit(alpha) * 255).roundToInt())

/** Feathers a layer into the paper above and below, between wallpaper heights [top] and [bottom], with Codex's [stops]. */
private fun DrawScope.featherEdges(cover: Cover, top: Double, bottom: Double, vararg stops: Pair<Float, Float>) {
    drawRect(
        Brush.verticalGradient(*stops.map { (at, alpha) -> at to Color.Black.copy(alpha = alpha) }.toTypedArray(), startY = cover.py(top), endY = cover.py(bottom)),
        topLeft = Offset(cover.x.toFloat(), cover.py(top)), size = Size(cover.w.toFloat(), cover.py(bottom) - cover.py(top)), blendMode = BlendMode.DstIn,
    )
}

/**
 * The sun on the city: soft fans of light from the sun when it is in the painting, a warm wash
 * on the sunlit facades with cloud shadows passing over, and its path on the water; at night
 * the moon's path on the water. On a layer of its own, feathered above and below. What comes
 * from the sun or moon in sight shows as much as the body does ([focus]).
 */
fun DrawScope.drawLighting(scene: CityScene, paintings: ScenePaintings, sky: SkyState, environment: Environment, focus: Double, x: Int, w: Int, h: Int) {
    val motion = SceneMotion.of(scene)
    val cover = Cover(x, w, h, scene)
    val sun = Sunlight.of(sky)
    val transmission = environment.transmission ?: (.58 + .42 * (1 - environment.cloudCover))
    val power = sun.strength * transmission * environment.fogVisibility * 2.25
    val moon = moonReflection(sky, motion) * focus
    if (power <= 0 && moon <= 0) return
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(x.toFloat(), 0f, (x + w).toFloat(), h.toFloat()), Paint())
    canvas.save()
    canvas.clipRect(x.toFloat(), cover.py(.13), (x + w).toFloat(), cover.py(.68))
    if (power > 0) {
        if (focus > 0) drawScattering(sky, sun, power * focus, environment, cover)
        drawFacades(scene, paintings, sky, sun, power, environment, cover)
        if (focus > 0) drawSunPath(motion, sky, sun, power * focus, environment, cover)
    }
    if (environment.fogVisibility > 0 && moon > 0) drawMoonPath(motion, sky, moon, environment, cover)
    canvas.restore()
    featherEdges(cover, .13, .68, 0f to 0f, .06f to 1f, .88f to 1f, 1f to 0f)
    canvas.restore()
}

private fun DrawScope.drawScattering(sky: SkyState, sun: Sunlight, power: Double, environment: Environment, cover: Cover) {
    val body = sky.body ?: return
    if (!sun.inFrame) return
    val w = cover.w.toFloat()
    val h = cover.height.toFloat()
    val sx = cover.px(body.x)
    val sy = cover.py(body.y)
    val breathe = .76 + .24 * (1 - environment.cloudCover)
    for (i in 0..1) {
        val targetX = cover.px(.5 + (.5 - body.x) * .36 + (i - .5) * .16 + .04 * sin(environment.advection * .067 + i * .7))
        val targetY = cover.py(.54 + i * .035)
        val spread = w * (.19f + i * .095f)
        val fan = Path().apply {
            moveTo(sx - w * .012f, sy)
            cubicTo(sx - w * .035f, sy + h * .08f, targetX - spread, targetY - h * .11f, targetX - spread, targetY)
            quadraticTo(targetX, targetY + h * .04f, targetX + spread, targetY)
            cubicTo(targetX + spread, targetY - h * .11f, sx + w * .035f, sy + h * .08f, sx + w * .012f, sy)
            close()
        }
        val opacity = power * breathe * (.12 + sun.warm * .035)
        val light = Brush.linearGradient(
            0f to rgba(255, 248, 223, 0.0), .22f to rgba(255, 241, 207, opacity * .65), .62f to rgba(255, 236, 195, opacity), 1f to rgba(255, 237, 207, 0.0),
            start = Offset(sx, sy), end = Offset(targetX, targetY),
        )
        val paint = Paint()
        light.applyTo(size, paint, 1f)
        paint.skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, maxOf(5 * cover.css, cover.w * .025).toFloat())
        drawContext.canvas.drawPath(fan, paint)
    }
}

private fun DrawScope.drawFacades(scene: CityScene, paintings: ScenePaintings, sky: SkyState, sun: Sunlight, power: Double, environment: Environment, cover: Cover) {
    val canvas = drawContext.canvas
    val w = cover.w.toFloat()
    val h = cover.height.toFloat()
    canvas.saveLayer(Rect(cover.x.toFloat(), 0f, cover.x + w, cover.h.toFloat()), Paint())
    // Brighter on the side the sun comes from.
    val side = sin((sky.sunAzimuth - scene.bearing) * PI / 180)
    val g = (249 - sun.warm * 14).roundToInt()
    val b = (228 - sun.warm * 27).roundToInt()
    val base = power * .48
    drawRect(
        Brush.horizontalGradient(listOf(rgba(255, g, b, base * (.72 - side * .28)), rgba(255, g, b, base * (.72 + side * .28))), startX = cover.x.toFloat(), endX = cover.x + w),
        topLeft = Offset(cover.x.toFloat(), cover.py(.25)), size = Size(w, h * .45f),
    )
    // Soft gaps in the wash drift over: passing cloud.
    for (i in 0..2) {
        val period = w * 1.8f
        val drift = (environment.advection * w * .009 * (.6 + i * .13)).toFloat()
        val cx = ((i * .52f * w + drift) % period) - w * .3f
        val cy = cover.py(.39 + i * .075)
        val rx = w * (.27f + i * .035f)
        val ry = h * .11f
        val shadow = (.18 + environment.cloudCover * .30).toFloat()
        for (center in listOf(cx - period, cx, cx + period)) {
            withTransform({
                translate(cover.x + center, cy)
                scale(rx, ry, pivot = Offset.Zero)
            }) {
                drawRect(
                    Brush.radialGradient(0f to Color.Black.copy(alpha = shadow), 1f to Color.Black.copy(alpha = 0f), center = Offset.Zero, radius = 1f),
                    topLeft = Offset(-1f, -1f), size = Size(2f, 2f), blendMode = BlendMode.DstOut,
                )
            }
        }
    }
    // Only where the day painting has facades to take it.
    drawImage(
        paintings.facades, srcOffset = IntOffset.Zero, srcSize = IntSize(paintings.facades.width, paintings.facades.height),
        dstOffset = IntOffset(cover.x, cover.top.roundToInt()), dstSize = IntSize(cover.w, cover.height.roundToInt()), blendMode = BlendMode.DstIn,
    )
    canvas.restore()
}

/** Rows down the water from [top] to [bottom] (wallpaper fractions), a CSS step apart, as Codex steps in CSS pixels. */
private inline fun rows(cover: Cover, top: Double, bottom: Double, step: Double, each: (yCss: Double, y: Float) -> Unit) {
    val cssHeight = cover.height / cover.css
    var yCss = top * cssHeight + 1
    while (yCss < bottom * cssHeight) {
        each(yCss, (cover.top + yCss * cover.css).toFloat())
        yCss += step
    }
}

private fun DrawScope.drawSunPath(motion: SceneMotion, sky: SkyState, sun: Sunlight, power: Double, environment: Environment, cover: Cover) {
    val body = sky.body ?: return
    if (!sun.inFrame || motion.water.isEmpty()) return
    val cssWidth = cover.w / cover.css
    val cssHeight = cover.height / cover.css
    val exclusions = motion.waterExclusions.map { it.path(cover) }
    motion.water.forEachIndexed { index, polygon ->
        val top = maxOf(.32, polygon.top)
        val bottom = minOf(.675, polygon.bottom)
        if (bottom <= top) return@forEachIndexed
        clipped(polygon.path(cover), exclusions) {
            rows(cover, top, bottom, maxOf(2.0, cssWidth * .009)) { yCss, y ->
                val distance = unit((yCss - cssHeight * .3) / (cssHeight * .36))
                val phase = yCss * .17 + index * 2.1
                val drift = sin(phase + environment.advection * .8) * cssWidth * .008 * (.8 + environment.waveGroup * .4)
                val half = cssWidth * (.012 + distance * .072) * (.7 + .3 * sin(phase * 1.7))
                val center = cover.px(body.x) + (drift * cover.css).toFloat()
                val alpha = power * (.17 + sun.warm * .055) * (.55 + .45 * sin(phase + environment.advection * .7).pow(2))
                drawStreak(center, y, (half * cover.css).toFloat(), maxOf(.5, cssWidth * .0025) * cover.css, rgba(255, 245, 212, 0.0), rgba(255, 246, 216, alpha), BlendMode.SrcOver)
            }
        }
    }
}

private fun DrawScope.drawMoonPath(motion: SceneMotion, sky: SkyState, strength: Double, environment: Environment, cover: Cover) {
    val body = sky.body ?: return
    val cssWidth = cover.w / cover.css
    val cssHeight = cover.height / cover.css
    val transmission = unit(environment.transmission ?: 1.0)
    val exclusions = motion.waterExclusions.map { it.path(cover) }
    motion.water.forEachIndexed { index, polygon ->
        val top = maxOf(.3, polygon.top)
        val bottom = minOf(.68, polygon.bottom)
        clipped(polygon.path(cover), exclusions) {
            rows(cover, top, bottom, maxOf(2.5, cssWidth * .011)) { yCss, y ->
                val depth = unit((yCss - top * cssHeight) / maxOf(1.0, (bottom - top) * cssHeight))
                val phase = yCss * .19 + index * 1.2
                val center = cover.px(body.x) + (sin(phase + environment.advection * .72) * cssWidth * .012 * cover.css).toFloat()
                val half = cssWidth * (.012 + depth * .055) * (.8 + .2 * sin(phase * 1.7))
                val alpha = strength * transmission * environment.fogVisibility * 1.95 * (.32 + .68 * sin(phase + environment.advection * .58).pow(2))
                drawStreak(center, y, (half * cover.css).toFloat(), maxOf(.6, cssWidth * .0025) * cover.css, rgba(181, 210, 225, 0.0), rgba(195, 220, 231, alpha), BlendMode.Screen)
            }
        }
    }
}

/** One stroke of light across the water, clear at both ends. */
private fun DrawScope.drawStreak(center: Float, y: Float, half: Float, width: Double, edge: Color, middle: Color, blend: BlendMode) {
    if (half <= 0f) return
    drawLine(
        Brush.horizontalGradient(0f to edge, .5f to middle, 1f to edge, startX = center - half, endX = center + half),
        start = Offset(center - half, y), end = Offset(center + half, y), strokeWidth = width.toFloat(), blendMode = blend,
    )
}

/**
 * The weather over the city: the sky darkening under heavy cloud, fog lying over the water and
 * the low town, a far curtain of rain, raindrops or snowflakes falling, rain ringing the water.
 * Only between the sky's upper band and the dashboard, feathered into both.
 */
fun DrawScope.drawWeather(scene: CityScene, sky: SkyState, weather: WeatherProfile, environment: Environment, seconds: Double, x: Int, w: Int, h: Int) {
    val cover = Cover(x, w, h, scene)
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(x.toFloat(), 0f, (x + w).toFloat(), h.toFloat()), Paint())
    canvas.save()
    canvas.clipRect(x.toFloat(), cover.py(.13), (x + w).toFloat(), cover.py(.70))
    drawAtmosphere(scene, sky.daylight, weather, seconds, cover)
    val fall = Precipitation.of(scene)
    drawRain(fall, sky.daylight, weather, seconds, cover)
    drawSnow(fall, weather, seconds, cover)
    drawRings(scene, fall, sky.daylight, weather, seconds, cover)
    canvas.restore()
    featherEdges(cover, .13, .70, 0f to 0f, .07f to 1f, .73f to 1f, .91f to 0f, 1f to 0f)
    canvas.restore()
}

private fun DrawScope.drawAtmosphere(scene: CityScene, day: Double, p: WeatherProfile, time: Double, cover: Cover) {
    val w = cover.w.toFloat()
    val h = cover.height.toFloat()
    val rainMass = p.rain * .6 + p.snow * .22
    val darkness = p.cloudCover * (.035 + .20 * rainMass)
    drawRect(
        Brush.verticalGradient(0f to rgba(67, 79, 85, darkness), .46f to rgba(86, 99, 105, darkness * .6), 1f to rgba(86, 99, 105, 0.0), startY = cover.py(.13), endY = cover.py(.64)),
        topLeft = Offset(cover.x.toFloat(), cover.py(.13)), size = Size(w, h * .53f),
    )
    val fog = unit(p.fog * .27 + rainMass * .075 + p.wetness * (1 - p.rain) * .025)
    if (fog > .0001) {
        val mist = SceneMotion.of(scene).mist
        val area = if (mist.isEmpty()) null else Path().apply { mist.forEach { addPath(it.path(cover)) } }
        clipped(area, emptyList()) {
            val horizonY = cover.py(((scene.cloudBand?.second ?: .267) + .07).coerceIn(.27, .4))
            val (r, g, b) = if (day > .45) Triple(217, 220, 214) else Triple(118, 134, 143)
            for (layer in 0..2) {
                val cx = cover.x + w * (.18f + layer * .34f + (.07 * sin(time * (.017 + layer * .004) + layer)).toFloat())
                val cy = horizonY + h * (.012 * sin(time * .025 + layer) + layer * .018).toFloat()
                withTransform({ scale(1f, .38f, pivot = Offset(cx, cy)) }) {
                    drawRect(
                        Brush.radialGradient(0f to rgba(r, g, b, fog * (.58 - layer * .1)), 1f to rgba(r, g, b, 0.0), center = Offset(cx, cy), radius = w * (.49f + layer * .08f)),
                        topLeft = Offset(cover.x.toFloat(), cy - w), size = Size(w, w * 2),
                    )
                }
            }
        }
    }
    if (p.rain > .02) {
        // A far curtain of rain over part of the city, not a blanket over all of it.
        val cx = cover.x + w * (.48f + (.26 * sin(time * .009 + (Seeds.of(scene.id).toUInt() % 61u).toInt())).toFloat())
        val cy = cover.py(.365)
        val radius = w * .34f
        val opacity = p.rain * (if (day > .3) .065 else .045)
        withTransform({
            translate(cx, cy)
            scale(.7f, 1.35f, pivot = Offset.Zero)
        }) {
            drawRect(
                Brush.radialGradient(0f to rgba(111, 133, 143, opacity), .6f to rgba(111, 133, 143, opacity * .48), 1f to rgba(111, 133, 143, 0.0), center = Offset.Zero, radius = radius),
                topLeft = Offset(-radius, -radius), size = Size(radius * 2, radius * 2),
            )
        }
    }
}

private fun DrawScope.drawRain(fall: Precipitation, day: Double, p: WeatherProfile, time: Double, cover: Cover) {
    val count = (210 * p.rain).roundToInt()
    val w = cover.w
    val h = cover.height
    for (i in 0 until count) {
        val drop = fall.drops[i]
        val x = (cover.x + (wrap(drop.x + time * drop.drift, 1.0) * 1.18 - .09) * w).toFloat()
        val y = cover.py(.1 + wrap(drop.y + time * drop.speed * (.6 + drop.depth * .8), 1.0) * .65)
        val length = h * (.003 + drop.depth * .012) * (.6 + p.rain * .5) * 1.18
        val dx = length * (.18 + p.wind * .2 + .09 * sin(time * .13 + drop.phase))
        val opacity = (.065 + drop.depth * .12) * drop.alpha * (.65 + p.rain * .35) * 1.2
        val color = if (day > .35) rgba(110, 135, 148, opacity) else rgba(187, 204, 207, opacity * .95)
        drawLine(color, Offset(x, y), Offset(x + dx.toFloat(), y + length.toFloat()), strokeWidth = ((.35 + drop.depth * .65) * cover.css).toFloat(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawSnow(fall: Precipitation, p: WeatherProfile, time: Double, cover: Cover) {
    val count = (125 * p.snow).roundToInt()
    for (i in 0 until count) {
        val flake = fall.flakes[i]
        val nx = wrap(flake.x + time * flake.drift + .023 * p.wind * sin(time / flake.period + flake.phase), 1.0)
        val y = cover.py(.1 + wrap(flake.y + time * flake.speed, 1.0) * .65)
        val radius = ((.45 + flake.depth * 1.4) * cover.css).toFloat()
        drawOval(rgba(242, 244, 239, .18 + flake.depth * .36), topLeft = Offset(cover.px(nx) - radius, y - radius * .85f), size = Size(radius * 2, radius * 1.7f))
    }
}

private fun DrawScope.drawRings(scene: CityScene, fall: Precipitation, day: Double, p: WeatherProfile, time: Double, cover: Cover) {
    val amount = (fall.rings.size * p.rain).roundToInt()
    if (amount == 0) return
    val motion = SceneMotion.of(scene)
    val water = Path().apply { motion.water.forEach { addPath(it.path(cover)) } }
    clipped(water, motion.waterExclusions.map { it.path(cover) }) {
        val stroke = Stroke(width = (.5 * cover.css).toFloat())
        for (i in 0 until amount) {
            val ring = fall.rings[i]
            val life = wrap(time / ring.period + ring.phase, 1.0)
            val x = cover.px(ring.x)
            val y = cover.py(ring.y)
            val radius = (cover.w * (.001 + .011 * life) * ring.scale).toFloat()
            val alpha = .27 * (1 - life) * smooth(life * 9) * (.55 + .45 * day)
            rotateRad(-.035f, pivot = Offset(x, y)) {
                drawOval(rgba(220, 232, 229, alpha), topLeft = Offset(x - radius, y - radius * .29f), size = Size(radius * 2, radius * .58f), style = stroke)
            }
            if (life < .2) {
                drawLine(
                    rgba(219, 231, 231, (1 - life * 5) * .19), Offset(x, y), Offset(x + radius * .1f, y - (cover.w * .004 * (1 - life * 5)).toFloat()),
                    strokeWidth = (.5 * cover.css).toFloat(),
                )
            }
        }
    }
}
