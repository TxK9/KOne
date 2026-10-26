package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.nekohasekai.sfa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DarkColorScheme =
    darkColorScheme(
        primary = SingBoxPrimary,
        secondary = SingBoxPrimaryLight,
        tertiary = LogBlue,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = SingBoxPrimary,
        secondary = SingBoxPrimaryDark,
        tertiary = LogBlue,
    )

internal data class EdgeVeils(val top: Float, val bottom: Float, val surface: Int)

/** Veil strength from the decoded photo under the top bar and the navigation, for 4.5:1 ink. */
@Composable
private fun rememberEdgeVeils(backdrop: BackdropContext?): EdgeVeils? {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(backdrop?.pixels, backdrop?.previousPixels, backdrop?.bounds, backdrop?.theme, backdrop?.focus) {
        val context = backdrop ?: return@remember null
        if (context.pixels == null || context.bounds.width <= 0f || context.bounds.height <= 0f) return@remember null
        val b = context.bounds
        val topPx = with(density) { 88.dp.toPx() }
        val bottomPx = with(density) { 80.dp.toPx() }
        fun support(area: Rect): Float = KninePalette.readingSupport(
            context.theme.ink, backdropColors(context, area), context.theme.readingSurface, 4.5f,
        ).alpha.coerceIn(0f, 0.7f)
        EdgeVeils(
            top = support(Rect(b.left, b.top, b.right, b.top + topPx)),
            bottom = support(Rect(b.left, b.bottom - bottomPx, b.right, b.bottom)),
            surface = context.theme.readingSurface,
        )
    }
}

@Composable
fun Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    portraitBackdrop: Boolean = false,
    backdropRes: Int = R.drawable.portrait_backdrop,
    customBackdropPath: String? = null,
    backdropRevision: Int = 0,
    plainKnine: Boolean = false,
    cityWallpaper: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val overlayState = remember { WallpaperOverlayState() }
    var backdropBounds by remember { mutableStateOf(Rect.Zero) }
    data class ReadyBackdrop(val resource: Int, val pixels: BackdropPixels?, val bitmap: ImageBitmap?, val focus: BackdropFocus = BackdropFocus())
    val ready by produceState<ReadyBackdrop?>(null, portraitBackdrop, backdropRes, customBackdropPath, backdropRevision, cityWallpaper) {
        value = if (portraitBackdrop) withContext(Dispatchers.Default) {
            ReadyBackdrop(
                backdropRes,
                if (cityWallpaper) null else if (customBackdropPath != null) loadBackdropPixels(customBackdropPath) else loadBackdropPixels(context.resources, backdropRes),
                if (customBackdropPath != null) loadDisplayedBackdrop(customBackdropPath)?.asImageBitmap() else null,
                WallpaperCatalog.focusFor(backdropRes),
            )
        } else null
    }
    var currentBackdrop by remember { mutableStateOf<ReadyBackdrop?>(null) }
    var leavingBackdrop by remember { mutableStateOf<ReadyBackdrop?>(null) }
    var settledTheme by remember { mutableStateOf(WallpaperCatalog.initialThemeFor(backdropRes)) }
    var displayedTheme by remember { mutableStateOf(WallpaperCatalog.initialThemeFor(backdropRes)) }
    var leavingTheme by remember { mutableStateOf(WallpaperTheme(true)) }
    var readingTopWindow by remember { mutableStateOf<Float?>(null) }
    var leavingReadingTop by remember { mutableStateOf<Float?>(null) }
    val reportReadingTop = remember { { top: Float? -> readingTopWindow = top } }
    val photoBoundary by animateFloatAsState(
        targetValue = readingTopWindow?.minus(backdropBounds.top) ?: backdropBounds.height,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "photoBoundary",
    )
    data class IncomingTheme(val frame: ReadyBackdrop, val theme: WallpaperTheme, val size: Size)
    val incomingTheme by produceState<IncomingTheme?>(null, ready, backdropBounds.size) {
        val incoming = ready
        val viewport = backdropBounds
        value = if (incoming?.pixels != null && viewport.width > 0 && viewport.height > 0) {
            withContext(Dispatchers.Default) {
                IncomingTheme(incoming, KninePalette.wallpaperTheme(sampledBackdropColors(incoming.pixels, viewport, viewport, incoming.focus)), viewport.size)
            }
        } else if (incoming != null && viewport.width > 0 && viewport.height > 0) {
            IncomingTheme(incoming, WallpaperTheme(true), viewport.size)
        } else null
    }
    val transition = remember { Animatable(1f) }
    val foregroundTransition = remember { Animatable(1f) }
    LaunchedEffect(ready, incomingTheme) {
        val next = ready ?: return@LaunchedEffect
        val nextTheme = incomingTheme?.takeIf { it.frame == next && it.size == backdropBounds.size }?.theme ?: return@LaunchedEffect
        if (currentBackdrop != next) {
            val firstImage = currentBackdrop == null
            if (!firstImage) foregroundTransition.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
            // Keep an opaque base until the incoming layer is ready at alpha zero.
            transition.snapTo(0f)
            leavingTheme = displayedTheme
            leavingReadingTop = readingTopWindow?.minus(backdropBounds.top)
            leavingBackdrop = currentBackdrop ?: ReadyBackdrop(
                0, BackdropPixels(1, 1, floatArrayOf(KninePalette.luminance(KninePalette.DARK_INK))), null,
            )
            currentBackdrop = next
            displayedTheme = nextTheme
            // The bright layout measures its reading boundary while content is
            // invisible. Classification continues to use the full visible crop.
            withFrameNanos { }
            withFrameNanos { }
            if (firstImage) transition.snapTo(1f)
            else transition.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            // Freeze one palette throughout the crossfade, then commit it once.
            settledTheme = nextTheme
            leavingBackdrop = null
            foregroundTransition.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        } else if (leavingBackdrop != null) {
            transition.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            settledTheme = nextTheme
            leavingBackdrop = null
            foregroundTransition.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        } else {
            displayedTheme = nextTheme
            settledTheme = nextTheme
            if (foregroundTransition.value < 1f) foregroundTransition.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        }
    }
    val activeBitmap = currentBackdrop?.bitmap
    val activePainter = if (portraitBackdrop) {
        if (activeBitmap != null) remember(activeBitmap) { BitmapPainter(activeBitmap) }
        else painterResource(currentBackdrop?.resource ?: backdropRes)
    } else null
    val backdrop = if (portraitBackdrop) remember(currentBackdrop, leavingBackdrop, backdropBounds, settledTheme, displayedTheme, reportReadingTop, activePainter) {
        BackdropContext(
            currentBackdrop?.pixels, backdropBounds, leavingBackdrop?.pixels,
            currentBackdrop?.focus ?: WallpaperCatalog.focusFor(backdropRes), leavingBackdrop?.focus ?: BackdropFocus(), settledTheme,
            displayedTheme, reportReadingTop, activePainter,
            panelAtTop = WallpaperCatalog.panelAtTopFor(currentBackdrop?.resource ?: backdropRes),
        )
    } else null
    val lightInk = settledTheme.lightInk
    val colorScheme =
        when {
            plainKnine -> plainKnineColorScheme(darkTheme)
            portraitBackdrop -> backdropColorScheme(lightInk).let {
                if (settledTheme.brightComposition) it else it.copy(outline = Color.Transparent, outlineVariant = Color.Transparent)
            }
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = if (portraitBackdrop) {
                    !lightInk
                } else !darkTheme
                isAppearanceLightNavigationBars = if (portraitBackdrop) {
                    !lightInk
                } else !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (portraitBackdrop) backdropTypography(Typography, lightInk) else Typography,
        shapes = Shapes,
    ) {
        if (portraitBackdrop) {
            CompositionLocalProvider(
                LocalBackdrop provides backdrop,
                LocalTransparentUi provides !settledTheme.brightComposition,
                LocalWallpaperOverlay provides overlayState,
            ) {
                Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { backdropBounds = it.boundsInWindow() }) {
                    @Composable
                    fun drawBackdrop(frame: ReadyBackdrop, opacity: Float, photoTheme: WallpaperTheme, readingTop: Float?) {
                        val imageModifier = Modifier.matchParentSize().graphicsLayer { alpha = opacity }
                        if (frame.resource == 0) {
                            Box(imageModifier.background(Color(KninePalette.DARK_INK)))
                            return
                        }
                        val bitmap = frame.bitmap
                        val painter = if (frame == currentBackdrop || currentBackdrop == null) activePainter!!
                        else if (bitmap != null) remember(bitmap) { BitmapPainter(bitmap) } else painterResource(frame.resource)
                        // Bright and dark photos alike are drawn whole and sharp.
                        Box(imageModifier) {
                            Canvas(Modifier.matchParentSize()) {
                                val sourceWidth = frame.pixels?.sourceWidth ?: painter.intrinsicSize.width.toInt()
                                val sourceHeight = frame.pixels?.sourceHeight ?: painter.intrinsicSize.height.toInt()
                                val transform = BackdropFraming.transform(sourceWidth, sourceHeight, size.width, size.height, frame.focus)
                                clipRect {
                                    translate(transform.x, transform.y) {
                                        with(painter) { draw(Size(transform.width, transform.height)) }
                                    }
                                }
                            }
                        }
                    }
                    if (currentBackdrop == null) {
                        drawBackdrop(ReadyBackdrop(backdropRes, null, null, WallpaperCatalog.focusFor(backdropRes)), 1f, displayedTheme, if (displayedTheme.brightComposition) photoBoundary else null)
                    } else {
                        leavingBackdrop?.let { drawBackdrop(it, 1f, leavingTheme, leavingReadingTop) }
                        currentBackdrop?.let { drawBackdrop(it, if (leavingBackdrop == null) 1f else transition.value, displayedTheme, if (displayedTheme.brightComposition) photoBoundary else null) }
                    }
                    if (cityWallpaper) {
                        CityWallpaper(Modifier.matchParentSize())
                        // Reading pages keep a steady veil through day and night. The dashboard lets the
                        // city through, shaded only behind the title above and the controls below.
                        val lively by animateFloatAsState(
                            if (CityWallpapers.lively) 1f else 0f,
                            tween(700, easing = FastOutSlowInEasing),
                            label = "cityVeil",
                        )
                        Canvas(Modifier.matchParentSize()) {
                            // A night-blue black, so a bright day scene recedes to dusk rather than to grey.
                            drawRect(Color(0xFF0A0D12).copy(alpha = CITY_WALLPAPER_VEIL_ALPHA + (CITY_WALLPAPER_LIVELY_VEIL - CITY_WALLPAPER_VEIL_ALPHA) * lively))
                            if (lively > 0f) {
                                val top = 172.dp.toPx()
                                drawRect(
                                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.44f * lively), 1f to Color.Transparent, endY = top),
                                    size = Size(size.width, top),
                                )
                                val bottom = size.height * 0.4f
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.5f to Color.Black.copy(alpha = 0.3f * lively),
                                        1f to Color.Black.copy(alpha = 0.72f * lively),
                                        startY = size.height - bottom,
                                        endY = size.height,
                                    ),
                                    topLeft = Offset(0f, size.height - bottom),
                                    size = Size(size.width, bottom),
                                )
                            }
                        }
                    } else if (customBackdropPath == null && (backdropRes == R.drawable.knine_plain_light || backdropRes == R.drawable.knine_plain_dark)) {
                        PlainBackdropMark(dark = backdropRes == R.drawable.knine_plain_dark, modifier = Modifier.matchParentSize())
                    }
                    // The photograph stays whole and sharp. Only the strips behind the
                    // top bar and the navigation get a veil, as strong as this photo needs.
                    val veils = rememberEdgeVeils(backdrop)
                    Canvas(Modifier.matchParentSize().graphicsLayer { alpha = foregroundTransition.value }) {
                        if (veils != null && size.height > 0f) {
                            val color = Color(veils.surface)
                            val top = 132.dp.toPx()
                            if (veils.top > 0f) {
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to color.copy(alpha = veils.top),
                                        0.64f to color.copy(alpha = veils.top * 0.72f),
                                        1f to Color.Transparent,
                                        startY = 0f,
                                        endY = top,
                                    ),
                                    size = Size(size.width, top),
                                )
                            }
                            val bottom = 168.dp.toPx()
                            if (veils.bottom > 0f) {
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.52f to color.copy(alpha = veils.bottom * 0.8f),
                                        1f to color.copy(alpha = veils.bottom),
                                        startY = size.height - bottom,
                                        endY = size.height,
                                    ),
                                    topLeft = Offset(0f, size.height - bottom),
                                    size = Size(size.width, bottom),
                                )
                            }
                        }
                    }
                    Box(Modifier.matchParentSize().graphicsLayer {
                        alpha = if (!settledTheme.brightComposition && overlayState.windows.intValue > 0) 0f else foregroundTransition.value
                    }) { content() }
                }
            }
        } else {
            content()
        }
    }
}
