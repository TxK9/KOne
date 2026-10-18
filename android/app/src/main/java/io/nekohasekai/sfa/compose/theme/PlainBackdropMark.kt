package io.nekohasekai.sfa.compose.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.ink.InkMountains
import io.nekohasekai.sfa.compose.theme.ink.InkPainter
import io.nekohasekai.sfa.compose.theme.ink.SkyClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** How long each mountain stays, and how long one fades into the next. */
private const val SCENE_SECONDS = 20.0
private const val FADE_SECONDS = 4.0

/**
 * Whether the page in front is the dashboard. Elsewhere the mountains stay but the seal and the
 * birds keep out of the way of the page's own content.
 */
internal object InkStage {
    val onDashboard = androidx.compose.runtime.mutableStateOf(true)

    /** The connected node's weather (Weather seal), set by the dashboard; null when not connected. */
    val weather = androidx.compose.runtime.mutableStateOf<io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather?>(null)
}

/** One painted mountain ready to show: its far ranges, the mountain itself, and its seal. */
private class ShownScene(val back: ImageBitmap, val front: ImageBitmap, val seal: ImageBitmap, val sealOnLeft: Boolean)

/**
 * With no wallpaper, Mountain landscapes in ink take turns behind the App: each mountain rises out of the
 * mist, drifts a little against its far ranges, shows its seal, and gives way to the next while
 * clouds pass and birds cross. Every painting is drawn once off the main thread (see
 * [InkPainter]); on screen only layers move and fade, about thirty times a second, only while
 * the App is in front, and not at all when the system turns animations off.
 */
@Composable
internal fun PlainBackdropMark(dark: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mountains = InkMountains.all
    // Start where the day has got to, so opening the App does not always show the same mountain.
    val first = remember { ((System.currentTimeMillis() / 1000 / SCENE_SECONDS.toLong()) % mountains.size).toInt() }
    val brush by produceState<android.graphics.Bitmap?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.kone_ink_mountains) }.getOrNull() }
    }
    val clock = remember { mutableDoubleStateOf(0.0) }
    val still = remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val motionScale = currentCoroutineContext()[MotionDurationScale]
            snapshotFlow { motionScale?.scaleFactor ?: 1f }.collectLatest { speed ->
                if (speed <= 0f) {
                    // Animations off: the first mountain, settled, with its seal.
                    still.value = true
                    clock.doubleValue = FADE_SECONDS + 2.0
                } else {
                    still.value = false
                    val resumedAt = clock.doubleValue
                    var origin: Long? = null
                    var previousUpdate = 0L
                    while (currentCoroutineContext().isActive) {
                        val now = withFrameNanos { it }
                        val start = origin ?: now.also { origin = it }
                        // A cheap frame callback gates layer writes to about 30 Hz, high-refresh phones included.
                        if (now - previousUpdate >= 32_000_000L) {
                            clock.doubleValue = resumedAt + (now - start) / 1_000_000_000.0 / speed
                            previousUpdate = now
                        }
                    }
                }
            }
        }
    }

    // The scene on screen, the one before it (still fading out) and the next (painted ahead).
    val scenes = remember(dark) { mutableStateMapOf<Int, ShownScene>() }
    val step = (clock.doubleValue / SCENE_SECONDS).toInt()
    LaunchedEffect(step, dark, brush) {
        val texture = brush ?: return@LaunchedEffect
        val wanted = listOf(step, step + 1)
        for (index in wanted) {
            if (scenes.containsKey(index)) continue
            val mountain = mountains[(first + index) % mountains.size]
            val painted = withContext(Dispatchers.Default) { runCatching { InkPainter.render(mountain, dark, texture) }.getOrNull() } ?: continue
            scenes[index] = ShownScene(painted.back.asImageBitmap(), painted.front.asImageBitmap(), painted.seal.asImageBitmap(), painted.sealOnLeft)
        }
        scenes.keys.filter { it < step - 1 || it > step + 1 }.forEach { scenes.remove(it) }
    }

    BoxWithConstraints(modifier) {
        val artWidth = maxWidth * 1.12f
        val artHeight = artWidth * (InkPainter.HEIGHT / InkPainter.WIDTH.toFloat())
        val artLeft = (maxWidth - artWidth) / 2
        val artTop = maxHeight * 0.085f
        val local = { (clock.doubleValue % SCENE_SECONDS).toFloat() }

        @Composable
        fun Painting(index: Int) {
            val scene = scenes[index] ?: return
            // The old mountain goes into the mist before the new one comes out of it: little overlap.
            val fade = {
                val u = local() / FADE_SECONDS.toFloat()
                val smooth = { t: Float -> t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) } }
                if (index == step) smooth((u - 0.35f) / 0.65f) else 1f - smooth(u / 0.6f)
            }
            val settle = { if (index == step) (1f - (local() / FADE_SECONDS.toFloat()).coerceIn(0f, 1f)).let { it * it } else 0f }
            val drift = { sin((clock.doubleValue / SCENE_SECONDS * 2.0 * PI).toFloat() + index) }
            Box(
                Modifier
                    .offset(artLeft, artTop)
                    .size(artWidth, artHeight)
                    .graphicsLayer {
                        alpha = fade() * 0.92f
                        translationX = -drift() * size.width * 0.006f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache { onDrawBehind { drawImage(scene.back, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium) } },
            )
            Box(
                Modifier
                    .offset(artLeft, artTop)
                    .size(artWidth, artHeight)
                    .graphicsLayer {
                        alpha = fade()
                        translationX = drift() * size.width * 0.012f
                        translationY = settle() * size.height * 0.03f
                        val grow = 1f + settle() * 0.025f
                        scaleX = grow
                        scaleY = grow
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache { onDrawBehind { drawImage(scene.front, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium) } },
            )
            // The seal comes once the mountain has settled and leaves before the next one arrives.
            val sealScale = artWidth / InkPainter.WIDTH.toFloat()
            val sealWidth = sealScale * scene.seal.width
            val sealHeight = sealScale * scene.seal.height
            Box(
                Modifier
                    .offset(if (scene.sealOnLeft) artLeft + artWidth * 0.07f else artLeft + artWidth * 0.93f - sealWidth, artTop + artHeight * 0.11f)
                    .size(sealWidth, sealHeight)
                    .graphicsLayer {
                        val u = local()
                        val shown = if (index == step) {
                            minOf(((u - FADE_SECONDS.toFloat() * 0.7f) / 1.6f).coerceIn(0f, 1f), ((SCENE_SECONDS.toFloat() - 1.2f - u) / 1.6f).coerceIn(0f, 1f))
                        } else 0f
                        alpha = if (!InkStage.onDashboard.value) 0f else if (still.value) 1f else shown
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache { onDrawBehind { drawImage(scene.seal, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium) } },
            )
        }

        // The sky keeps the time, behind the mountains so that what rises and sets does so behind
        // them. The light theme is day: a vermilion sun crosses from the left (east) to the right
        // (west). The dark theme is night: the moon stands where it really is, in its phase, among
        // a few faint stars. Only on the dashboard, like the seal.
        val sky by produceState(skyNow(dark), dark) {
            while (true) {
                value = skyNow(dark)
                delay(30_000)
            }
        }
        val skyShown by androidx.compose.animation.core.animateFloatAsState(
            if (InkStage.onDashboard.value) 1f else 0f,
            androidx.compose.animation.core.tween(900),
            label = "skyShown",
        )
        Canvas(Modifier.matchParentSize().graphicsLayer { alpha = skyShown; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
            val left = artLeft.toPx() + artWidth.toPx() * 0.10f
            val span = artWidth.toPx() * 0.80f
            val zenith = (artTop + artHeight * 0.10f).toPx()
            val horizon = (artTop + artHeight * 0.58f).toPx()
            val radius = artWidth.toPx() * 0.05f
            if (dark) {
                // Stars, faint and slow, high in the sky; the mountains hide the low ones.
                stars.forEachIndexed { index, (x, y, scale) ->
                    val twinkle = if (still.value) 0.5f else 0.5f + 0.5f * sin((clock.doubleValue / (5.0 + index % 4) * 2.0 * PI).toFloat() + index)
                    drawCircle(MOONLIGHT.copy(alpha = 0.18f + 0.32f * twinkle), radius * 0.045f * scale, Offset(size.width * x, zenith * 0.4f + (horizon - zenith * 0.4f) * y))
                }
            }
            val body = sky.body
            if (body.up) {
                val center = Offset(left + span * body.across, horizon - (horizon - zenith) * body.height)
                if (dark) drawMoon(center, radius * 0.86f, sky.phase) else drawSun(center, radius, body.height)
            }
        }

        Painting(step - 1)
        Painting(step)

        // Between two mountains the mist thickens over the whole painting, then thins again.
        val paper = if (dark) Color(0xFF181A1F) else Color(0xFFF0F2F5)
        Box(
            Modifier
                .offset(artLeft, artTop + artHeight * 0.18f)
                .size(artWidth, artHeight * 0.82f)
                .graphicsLayer {
                    val u = local() / FADE_SECONDS.toFloat()
                    alpha = if (step == 0 || still.value) 0f else (sin((u.coerceIn(0f, 1f) * PI).toFloat()) * 0.7f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawWithCache {
                    val veil = Brush.verticalGradient(0f to Color.Transparent, 0.35f to paper.copy(alpha = 0.85f), 0.8f to paper, 1f to paper)
                    onDrawBehind { drawRect(veil) }
                },
        )

        // Two banks of cloud drift across the feet of the mountains, in the paper's colour or a silver haze.
        val mist = if (dark) Color(0xFF9AA6AB) else Color(0xFFF0F2F5)
        for (bank in 0..1) {
            Box(
                Modifier
                    .offset(x = -maxWidth * 0.3f, y = artTop + artHeight * (if (bank == 0) 0.60f else 0.74f))
                    .size(maxWidth * 1.6f, artHeight * 0.12f)
                    .graphicsLayer {
                        val drift = sin((clock.doubleValue / (if (bank == 0) 46.0 else 61.0) * 2.0 * PI).toFloat())
                        translationX = drift * size.width * (if (bank == 0) 0.09f else -0.07f)
                        alpha = 0.75f + drift * 0.2f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache {
                        val strength = if (dark) 0.16f else 0.78f
                        val brushes = listOf(0.28f, 0.62f, 0.86f).map { cx ->
                            Offset(size.width * cx, size.height / 2f) to Brush.radialGradient(
                                0f to mist.copy(alpha = strength),
                                0.5f to mist.copy(alpha = strength * 0.55f),
                                1f to Color.Transparent,
                                center = Offset(size.width * cx, size.height / 2f),
                                radius = size.width * 0.22f,
                            )
                        }
                        val squash = size.height * 0.5f / (size.width * 0.22f)
                        onDrawBehind {
                            brushes.forEach { (center, paint) ->
                                scale(scaleX = 1f, scaleY = squash, pivot = center) { drawCircle(paint, size.width * 0.22f, center) }
                            }
                        }
                    },
            )
        }

        // Weather seal on the painting: while connected, a slow node brings mist drifting across the
        // mountains and one that does not answer is lost in fog; clear leaves the painting alone.
        val fog by androidx.compose.animation.core.animateFloatAsState(
            when (InkStage.weather.value) {
                io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather.Mist -> 0.6f
                io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather.Cut -> 1f
                else -> 0f
            },
            androidx.compose.animation.core.tween(2400),
            label = "weatherFog",
        )
        if (fog > 0.001f) {
            val veil = if (dark) mist.copy(alpha = 0.2f) else paper.copy(alpha = 0.6f)
            Box(
                Modifier
                    .offset(artLeft, artTop)
                    .size(artWidth, artHeight)
                    .graphicsLayer {
                        alpha = fog * fog
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache { onDrawBehind { drawRect(veil) } },
            )
            for (bank in 0..2) {
                Box(
                    Modifier
                        .offset(x = -maxWidth * 0.3f, y = artTop + artHeight * (0.24f + 0.15f * bank))
                        .size(maxWidth * 1.6f, artHeight * 0.16f)
                        .graphicsLayer {
                            val drift = sin((clock.doubleValue / (29.0 + bank * 9.0) * 2.0 * PI).toFloat() + bank)
                            translationX = drift * size.width * (if (bank % 2 == 0) 0.11f else -0.09f)
                            alpha = fog * (0.78f + drift * 0.18f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .drawWithCache {
                            val strength = if (dark) 0.24f else 0.92f
                            val brushes = listOf(0.18f, 0.5f, 0.8f).map { cx ->
                                Offset(size.width * cx, size.height / 2f) to Brush.radialGradient(
                                    0f to mist.copy(alpha = strength),
                                    0.5f to mist.copy(alpha = strength * 0.6f),
                                    1f to Color.Transparent,
                                    center = Offset(size.width * cx, size.height / 2f),
                                    radius = size.width * 0.24f,
                                )
                            }
                            val squash = size.height * 0.5f / (size.width * 0.24f)
                            onDrawBehind {
                                brushes.forEach { (center, paint) ->
                                    scale(scaleX = 1f, scaleY = squash, pivot = center) { drawCircle(paint, size.width * 0.24f, center) }
                                }
                            }
                        },
                )
            }
        }

        // Every other mountain, three distant birds cross the open sky and fade at the edges.
        val ink = if (dark) Color(0xFFCBD8D4) else Color(0xFF36564B)
        Box(
            Modifier
                .offset(x = maxWidth * 0.06f, y = artTop + artHeight * 0.16f)
                .size(maxWidth * 0.16f, maxWidth * 0.055f)
                .graphicsLayer {
                    val u = local() / SCENE_SECONDS.toFloat()
                    // Birds fly by day; at night (the dark theme) they rest.
                    val flying = step % 2 == 0 && !still.value && InkStage.onDashboard.value && !dark
                    val travel = ((u - 0.18f) / 0.7f).coerceIn(0f, 1f)
                    translationX = maxWidth.toPx() * 0.66f * travel
                    translationY = -maxWidth.toPx() * 0.03f * sin(travel * PI.toFloat())
                    val edge = minOf(travel / 0.08f, (1f - travel) / 0.12f).coerceIn(0f, 1f)
                    alpha = if (flying) edge * (if (dark) 0.7f else 0.78f) else 0f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawWithCache {
                    // Tapered curved brush shapes, not a bitmap, particle emitter or blur effect.
                    val wing = Path().apply {
                        moveTo(0f, 2f)
                        cubicTo(4f, 1f, 8f, 3f, 12f, 8f)
                        cubicTo(15f, 4f, 19f, 1f, 24f, 0f)
                        cubicTo(20f, 3f, 17f, 6f, 13f, 9.3f)
                        lineTo(12.1f, 11.7f)
                        lineTo(11f, 9.4f)
                        cubicTo(7f, 5.5f, 4f, 3f, 0f, 2f)
                        close()
                    }
                    val unit = size.width / 79f
                    onDrawBehind {
                        scale(unit, unit, pivot = Offset.Zero) {
                            for (bird in 0..2) {
                                translate(bird * 26f, if (bird == 1) 6f else if (bird == 2) 2f else 0f) {
                                    scale(1f - bird * 0.10f, 1f - bird * 0.10f, pivot = Offset.Zero) { drawPath(wing, ink) }
                                }
                            }
                        }
                    }
                },
        )
    }
}

private val MOONLIGHT = Color(0xFFEDE6CF)

/** Where the stars are, as shares of the width and of the sky's height, and how large. */
private val stars = listOf(
    Triple(0.08f, 0.05f, 1.0f), Triple(0.17f, 0.22f, 0.7f), Triple(0.26f, 0.09f, 0.8f), Triple(0.34f, 0.31f, 0.6f),
    Triple(0.43f, 0.03f, 0.9f), Triple(0.52f, 0.18f, 0.7f), Triple(0.61f, 0.07f, 1.1f), Triple(0.69f, 0.27f, 0.6f),
    Triple(0.77f, 0.12f, 0.8f), Triple(0.86f, 0.04f, 0.7f), Triple(0.93f, 0.20f, 0.9f), Triple(0.12f, 0.38f, 0.5f),
    Triple(0.57f, 0.40f, 0.5f), Triple(0.81f, 0.36f, 0.6f),
)

/** The body the theme shows now: the sun for the light theme, the moon (with its phase) for the dark. */
private class SkyNow(val body: SkyClock.Body, val phase: Float)

private fun skyNow(dark: Boolean): SkyNow {
    val now = System.currentTimeMillis()
    return if (dark) SkyClock.moon(now).let { SkyNow(it.body, it.phase) } else SkyNow(SkyClock.sun(now), 0f)
}

/** The sun as ink painters set it down: a flat vermilion disc with a soft rim, deeper and larger low in the sky. */
private fun DrawScope.drawSun(center: Offset, radius: Float, height: Float) {
    val low = ((0.25f - height) / 0.25f).coerceIn(0f, 1f)
    val r = radius * (1f + 0.18f * low)
    val core = lerp(Color(0xFFD0503A), Color(0xFFB8321F), low)
    drawCircle(Brush.radialGradient(0f to core.copy(alpha = 0.20f), 0.45f to core.copy(alpha = 0.07f), 1f to Color.Transparent, center = center, radius = r * 3.2f), r * 3.2f, center)
    drawCircle(core.copy(alpha = 0.35f), r * 1.07f, center)
    drawCircle(Brush.radialGradient(0f to core.copy(alpha = 0.9f), 0.85f to core, 1f to core.copy(alpha = 0.92f), center = center, radius = r), r, center)
}

/** The moon in its phase: the lit part in pale light, the rest only traced (earthshine), inside a halo. */
private fun DrawScope.drawMoon(center: Offset, radius: Float, phase: Float) {
    drawCircle(Brush.radialGradient(0f to MOONLIGHT.copy(alpha = 0.16f), 0.4f to MOONLIGHT.copy(alpha = 0.06f), 1f to Color.Transparent, center = center, radius = radius * 3.4f), radius * 3.4f, center)
    drawCircle(MOONLIGHT.copy(alpha = 0.10f), radius, center)
    // Waxing, the right side is lit; the terminator is half an ellipse, as wide as the phase says.
    val waxing = phase < 0.5f
    val half = radius * cos(2.0 * PI * (if (waxing) phase else 1f - phase)).toFloat()
    val lit = Path().apply {
        arcTo(Rect(center, radius), -90f, 180f, true)
        arcTo(Rect(center.x - abs(half), center.y - radius, center.x + abs(half), center.y + radius), 90f, if (half > 0f) -180f else 180f, false)
        close()
    }
    scale(if (waxing) 1f else -1f, 1f, pivot = center) { drawPath(lit, MOONLIGHT) }
}
