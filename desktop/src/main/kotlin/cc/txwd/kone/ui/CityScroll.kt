package cc.txwd.kone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.translate
import cc.txwd.kone.city.Ambient
import cc.txwd.kone.city.Body
import cc.txwd.kone.city.Cities
import cc.txwd.kone.city.CityAir
import cc.txwd.kone.city.CityGallery
import cc.txwd.kone.city.CityScene
import cc.txwd.kone.city.CityWeather
import cc.txwd.kone.city.Sky
import cc.txwd.kone.city.Soundscape
import cc.txwd.kone.city.WeatherProfile
import cc.txwd.kone.city.bodyShows
import cc.txwd.kone.city.smooth
import cc.txwd.kone.ink.InkGallery
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The soundscape the city scroll is heard through, when the person turned it on and can see it; otherwise null. */
val LocalAmbient = compositionLocalOf<Ambient?> { null }

/**
 * The handscroll of a country's cities (Codex's country preview): four of its scenes side by
 * side, panning and dissolving into each other as the ink scroll does. Each city is in its own
 * daylight: the night painting under the day one, which shows as the sun climbs from 6° below
 * the horizon to 12° above; the sun or the moon stands where it is over that city now. Over each
 * painting the water sways, clouds drift and windows glow as in Codex's preview, boats cross,
 * birds fly, trees move and cars and people go about, the sun warms the facades and the city's
 * real weather falls on it. [at] fixes the instant (the screen
 * tests); otherwise it is now, looked at again every half minute.
 */
@Composable
fun CityScroll(
    country: String?, modifier: Modifier = Modifier, moving: Boolean = true, at: Instant? = null,
    /** The weather by city, fixed (the screen tests); otherwise the real weather, when it can be had. */
    weather: Map<String, WeatherProfile>? = null,
) {
    val date = (at ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalDate()
    val scenes = remember(country, date) { Cities.pick(country, date) }
    if (scenes.isEmpty()) {
        Handscroll(modifier, moving)
        return
    }
    var paintings by remember(scenes) { mutableStateOf(CityGallery.ready(scenes)) }
    val shown = remember(scenes) { Animatable(if (paintings != null) 1f else 0f) }
    LaunchedEffect(scenes) {
        if (paintings == null) {
            paintings = CityGallery.load(scenes)
            shown.animateTo(1f, tween(900))
        }
    }
    var skies by remember(scenes, at) { mutableStateOf(scenes.map { Sky.at(it, (at ?: Instant.now()).toEpochMilli()) }) }
    // Looked at again while the scroll is in sight, at once when it comes back into sight.
    if (at == null && moving) {
        LaunchedEffect(scenes) {
            while (true) {
                skies = scenes.map { Sky.at(it, System.currentTimeMillis()) }
                delay(30_000)
            }
        }
    }
    // The real weather over the cities, asked for while the scroll is in sight; fair weather until it comes.
    var live by remember(scenes) { mutableStateOf(weather ?: emptyMap()) }
    if (weather == null && at == null && moving) {
        LaunchedEffect(scenes) {
            while (true) {
                live = withContext(Dispatchers.IO) { CityWeather.current(scenes) }
                delay(60_000)
            }
        }
    }
    val airs = remember(scenes) { scenes.map { CityAir(it.id) } }
    // The city nearest the middle, as last drawn: the one shown with its sun or moon, and heard.
    val focused = remember(scenes) { IntArray(1) }
    val ambient = LocalAmbient.current
    if (ambient != null) {
        LaunchedEffect(ambient, scenes) {
            while (true) {
                val scene = scenes[focused[0].coerceIn(scenes.indices)]
                val weather = live[scene.cityId]
                ambient.mix = Soundscape.Mix(weather?.wind ?: 1.0, weather?.rain ?: 0.0, scene.waterType ?: "land")
                delay(1_000)
            }
        }
        DisposableEffect(ambient) { onDispose { ambient.mix = null } }
    }
    // Codex's motion runs at up to thirty frames a second, fewer where frames cost more; the pan rides the same clock.
    val software = LocalSoftware.current
    val cost = remember { FrameCost() }
    val clock = rememberClock(moving) { if (software) 4 else cost.perSecond(most = 30, least = 12) }
    val loaded = paintings ?: return
    // Each panel's painting blended for its daylight at its size, kept until either changes.
    val panels = remember(loaded) { arrayOfNulls<PanelPainting>(scenes.size) }
    Canvas(modifier.fillMaxSize()) {
        val started = System.nanoTime()
        val seconds = clock.value
        var nearest = -1.0
        drawStrip(scenes.size, panAt(seconds), shown.value, closedEnds = true) { index, x, w, h ->
            val scene = scenes[index]
            val sky = skies[index]
            val panel = panels[index]?.takeIf { it.fits(w, h, sky.daylight) }
                ?: PanelPainting.make(scene, loaded[index], sky.daylight, w, h, panels[index]?.takeIf { it.image.width == w && it.image.height == h }?.banks)
                    .also { panels[index] = it }
            drawImage(panel.image, topLeft = Offset(x.toFloat(), 0f))
            val air = airs[index].at(seconds, live[scene.cityId])
            // One sun or moon in the sky at a time: the city nearest the middle shows its own,
            // handing it to the next as the scroll pans.
            val off = kotlin.math.abs(x + w / 2f - size.width / 2) / (w * (1 - InkGallery.SHARED))
            val focus = 1 - smooth(.4, .6, off.toDouble())
            if (focus > nearest) {
                nearest = focus
                focused[0] = index
            }
            // In software every layer costs a good part of a core: the painting, the sun and moon alone.
            // Codex's layers in Codex's order: the water and clouds, boats and birds, sunlight,
            // the sun or moon, the life of the city, the weather over all.
            if (!software) {
                drawMotion(scene, panel, loaded[index].lights, sky, air, seconds, x, w, h)
                drawMarine(scene, sky, air, seconds, x, w, h)
                drawLighting(scene, loaded[index], sky, air, focus, x, w, h)
            }
            if (focus > 0) {
                sky.body?.let { drawBody(scene, it, x.toFloat(), w.toFloat(), h.toFloat(), (focus * bodyShows(air.weather, sky.daylight < .5, air.fogVisibility)).toFloat()) }
            }
            if (!software) {
                drawLife(scene, panel, sky.daylight, air, seconds, x, w, h)
                air.weather?.let { drawWeather(scene, sky, it, air, seconds, x, w, h) }
            }
        }
        cost.add(System.nanoTime() - started)
    }
}

/** The sun or moon of [scene], drawn as Codex's sky layer draws it, in the open sky only; [shows] less through cloud and fog. */
private fun DrawScope.drawBody(scene: CityScene, body: Body, left: Float, w: Float, h: Float, shows: Float) {
    fun px(x: Double) = left + (x * w).toFloat()
    fun py(y: Double) = ((y - scene.crop.top) / scene.crop.height * h).toFloat()
    val sky = scene.skySafe
    val open = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(px(sky.left), py(sky.top), px(sky.right), py(sky.bottom)))
        scene.skyExclusions.forEach { addRect(Rect(px(it.left), py(it.top), px(it.right), py(it.bottom))) }
    }
    val center = Offset(px(body.x), py(body.y))
    val r = (body.radius * w).toFloat()
    clipRect(px(sky.left), py(sky.top), px(sky.right), py(sky.bottom)) {
        clipPath(open, ClipOp.Intersect) {
            val faded = shows < 1f
            if (faded) drawContext.canvas.saveLayer(Rect(center, 3.4f * r), Paint().apply { alpha = shows })
            if (body.moon) drawMoon(center, r, body.illumination.toFloat(), body.tilt.toFloat()) else drawSun(center, r)
            if (faded) drawContext.canvas.restore()
        }
    }
}

/** Flat, quiet pigment: a faint haze outside, a soft edge, no highlight. */
private fun DrawScope.drawSun(center: Offset, r: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(230, 186, 125, 33), 1f / 3.4f to Color(230, 186, 125, 33), 1f to Color(230, 186, 125, 0), center = center, radius = 3.4f * r),
        radius = 3.4f * r, center = center,
    )
    for (edge in 4 downTo 1) drawCircle(Color(199, 164, 123).copy(alpha = 0.025f), radius = r * (1 + edge * 0.022f), center = center)
    drawCircle(Color(218, 169, 102).copy(alpha = 0.88f), radius = r, center = center)
}

/** The moon lit as much as it is, its lit side toward the sun, with a few faint maria. */
private fun DrawScope.drawMoon(center: Offset, r: Float, lit: Float, tilt: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(199, 216, 224).copy(alpha = 0.12f * lit), 1f / 3f to Color(199, 216, 224).copy(alpha = 0.12f * lit), 1f to Color(199, 216, 224, 0), center = center, radius = 3f * r),
        radius = 3f * r, center = center,
    )
    translate(center.x, center.y) {
        rotateRad(tilt, pivot = Offset.Zero) {
            drawCircle(Color(173, 191, 204).copy(alpha = 0.035f), radius = r, center = Offset.Zero)
            // The lit part: the right half-disc closed by the terminator, so its area is the lit fraction.
            val steps = 160
            val phase = Path().apply {
                for (i in 0..steps) {
                    val y = -1f + i * 2f / steps
                    val x = sqrt((1 - y * y).coerceAtLeast(0f))
                    if (i == 0) moveTo(x * r, y * r) else lineTo(x * r, y * r)
                }
                for (i in steps downTo 0) {
                    val y = -1f + i * 2f / steps
                    lineTo(-(2 * lit.coerceIn(0f, 1f) - 1) * sqrt((1 - y * y).coerceAtLeast(0f)) * r, y * r)
                }
                close()
            }
            drawPath(phase, Color(218, 227, 224).copy(alpha = 0.84f))
            clipPath(phase) {
                for ((mx, my, mr) in listOf(Triple(.25f, -.3f, .19f), Triple(-.2f, .1f, .27f), Triple(.4f, .35f, .14f), Triple(-.3f, -.5f, .11f))) {
                    drawCircle(Color(108, 128, 140).copy(alpha = 0.055f), radius = mr * r, center = Offset(mx * r, my * r))
                }
            }
        }
    }
}

