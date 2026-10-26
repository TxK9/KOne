package io.nekohasekai.sfa.compose.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The city is the canvas of every page. Nothing veils it whole: each label and icon says where it
 * sits, and a night-blue shade gathers behind those lines only, as deep as the scene under them
 * needs for 4.5:1 (3:1 for icons and large titles). Above, below and between the text the city shows
 * as it does on the dashboard. The city never moves between pages; only the shade, and a slight
 * depth of field behind full pages and popups, ease in and out.
 */

/** Night-blue black: a bright day scene recedes to dusk under it rather than to grey. */
internal const val CITY_SHADE = 0xFF0A0D12.toInt()

/** The deepest shade a line ever gets; any more and the city behind it would be gone. */
internal const val CITY_SHADE_MAX = 0.8f

/** Every line keeps at least this much, for the lights, aircraft and weather the map cannot see. */
internal const val CITY_SHADE_MIN = 0.22f

/** Over the faint shared veil, a see-through popup brings the whole city to [CITY_WALLPAPER_VEIL_ALPHA]. */
internal const val CITY_FOCUS_VEIL = 1f - (1f - CITY_WALLPAPER_VEIL_ALPHA) / (1f - CITY_WALLPAPER_LIVELY_VEIL)

private val CELL = 4.dp
private val LINE_MARGIN = 6.dp
private val BRIDGE = 56.dp
private val SOFTEN = 12.dp
private val FEATHER = 40.dp
private val DEPTH_BLUR = 2.dp
private val FOCUS_BLUR = 4.dp

/** What the running wallpaper last showed: its scene, and how far day has come there, 0 night to 1 day. */
internal object CityLight {
    data class Reading(val scene: String, val daylight: Float)

    var shown by mutableStateOf<Reading?>(null)
        private set

    /** The day image's opacity, as wallpaper.js sets it on the stage. Nothing is sent to the page. */
    const val QUERY = "(function(){var s=document.getElementById('stage');return s?s.style.getPropertyValue('--daylight'):''})()"

    /** Takes [result] of [QUERY]; a page that is not ready yet answers with an empty string. */
    fun read(scene: String, result: String?): Boolean {
        val level = result?.trim()?.removeSurrounding("\"")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return false
        shown = Reading(scene, level.coerceIn(0f, 1f))
        return true
    }
}

/** The scene as the shade measures it: a reduced map of the artwork, laid out as the wallpaper's stage lays it. */
internal class CityShade(private val width: Int, private val height: Int, private val text: FloatArray, private val large: FloatArray) {
    /** The shade [area] of a [canvas]-sized screen needs: the most any map cell under it asks for. */
    fun need(area: Rect, canvas: Size, large: Boolean): Float {
        if (area.isEmpty || canvas.width <= 0f || canvas.height <= 0f) return 0f
        // wallpaper.js: the stage is the screen's width at the artwork's aspect, scaled up to cover, centred.
        val stageHeight = canvas.width * height / width
        val scale = max(1f, canvas.height / stageHeight)
        val drawnWidth = canvas.width * scale
        val drawnHeight = stageHeight * scale
        val left = (canvas.width - drawnWidth) / 2f
        val top = (canvas.height - drawnHeight) / 2f
        fun column(x: Float) = ((x - left) / drawnWidth * width).toInt().coerceIn(0, width - 1)
        fun row(y: Float) = ((y - top) / drawnHeight * height).toInt().coerceIn(0, height - 1)
        val map = if (large) this.large else text
        var most = 0f
        for (y in row(area.top)..row(area.bottom - 0.5f)) {
            for (x in column(area.left)..column(area.right - 0.5f)) most = max(most, map[y * width + x])
        }
        return most
    }

    companion object {
        /** The sky, weather and lights the page paints over the artwork can lift it a little. */
        private const val SCENE_LIFT = 0.08f

        /**
         * Measures the day image at [daylight] over the night image, as the stage blends them. Without a
         * reading yet, each cell takes the brighter of the two, so the first frame is never too light.
         */
        fun measure(width: Int, height: Int, day: IntArray, night: IntArray, daylight: Float?): CityShade {
            val text = FloatArray(width * height)
            val large = FloatArray(width * height)
            val shade = CITY_SHADE
            for (i in 0 until width * height) {
                val seen = if (daylight != null) KninePalette.composite(day[i], night[i], daylight)
                else if (KninePalette.luminance(day[i]) >= KninePalette.luminance(night[i])) day[i] else night[i]
                val lifted = KninePalette.composite(0xFFFFFFFF.toInt(), seen, SCENE_LIFT)
                // Every page keeps the dashboard's faint veil under the shade.
                val under = KninePalette.composite(shade, lifted, CITY_WALLPAPER_LIVELY_VEIL)
                // The accent is the dimmer of the two light inks; meeting it meets both.
                text[i] = KninePalette.minimumSupportAlpha(KninePalette.LIGHT_ACCENT, under, shade, 4.5f)
                large[i] = KninePalette.minimumSupportAlpha(KninePalette.LIGHT_ACCENT, under, shade, 3f)
            }
            return CityShade(width, height, text, large)
        }

        private class Artwork(val scene: String, val width: Int, val height: Int, val day: IntArray, val night: IntArray)

        @Volatile private var artwork: Artwork? = null

        fun load(context: Context, scene: String, daylight: Float?): CityShade? {
            val id = scene.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: return null
            val art = artwork?.takeIf { it.scene == id } ?: decode(context, id)?.also { artwork = it } ?: return null
            return measure(art.width, art.height, art.day, art.night, daylight)
        }

        private fun decode(context: Context, id: String): Artwork? = runCatching {
            // A sixteenth of the artwork: each cell is a few dp of screen, enough to follow the skyline.
            fun read(time: String): Bitmap? = context.assets.open("wallpaper/images/$id-$time.webp").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 })
            }
            val day = read("day") ?: return null
            val night = read("night")?.let {
                if (it.width == day.width && it.height == day.height) it else Bitmap.createScaledBitmap(it, day.width, day.height, true)
            } ?: day
            fun pixels(bitmap: Bitmap) = IntArray(day.width * day.height).also { bitmap.getPixels(it, 0, day.width, 0, 0, day.width, day.height) }
            Artwork(id, day.width, day.height, pixels(day), pixels(night))
        }.getOrNull()
    }
}

/** The shade down the screen, built from the lines of text over the city. Pure, so it can be checked on its own. */
internal object CityScrim {
    /** A line over the city: its vertical extent on the canvas and the shade it needs. */
    data class Line(val top: Float, val bottom: Float, val alpha: Float)

    /**
     * One value per [cell] px down the canvas. Lines closer than [bridge] join, so a list reads as one
     * band rather than stripes; steps between lines soften over [soften] without any line getting
     * less than it needs; the band's ends fade into the open city over [feather].
     */
    fun profile(lines: List<Line>, cells: Int, cell: Float, bridge: Float, soften: Float, feather: Float): FloatArray {
        val core = FloatArray(cells)
        val height = cells * cell
        for (line in lines) {
            if (line.alpha <= 0f || line.bottom <= line.top || line.bottom <= 0f || line.top >= height) continue
            val from = (line.top / cell).toInt().coerceIn(0, cells - 1)
            val to = ((line.bottom / cell).toInt()).coerceIn(0, cells - 1)
            for (i in from..to) core[i] = max(core[i], line.alpha)
        }
        // Bridge the gaps between rows and groups.
        var last = -1
        val span = (bridge / cell).toInt()
        for (i in 0 until cells) {
            if (core[i] <= 0f) continue
            if (last >= 0 && i - last > 1 && i - last - 1 <= span) {
                for (j in last + 1 until i) core[j] = core[last] + (core[i] - core[last]) * (j - last) / (i - last)
            }
            last = i
        }
        // Widen by the softening reach, then average over it: every cell still gets at least its need.
        val reach = max(1, (soften / cell).roundToInt())
        val wide = FloatArray(cells) { i ->
            var most = 0f
            for (j in max(0, i - reach)..minOf(cells - 1, i + reach)) most = max(most, core[j])
            most
        }
        val out = FloatArray(cells) { i ->
            var sum = 0f
            for (j in i - reach..i + reach) sum += wide[j.coerceIn(0, cells - 1)]
            max(core[i], sum / (2 * reach + 1))
        }
        // Fade the band's ends out into the open city.
        val fade = max(1, ceil(feather / cell).toInt())
        for (i in 0 until cells) {
            if (core[i] <= 0f) continue
            for (d in 1..fade) {
                val t = 1f - d.toFloat() / fade
                val weight = core[i] * t * t * (3f - 2f * t)
                if (i - d >= 0 && out[i - d] < weight) out[i - d] = weight
                if (i + d < cells && out[i + d] < weight) out[i + d] = weight
            }
        }
        return out
    }

    /** Moves [shown] toward [target] over [dt] ms: quick to deepen behind new text, slower to give the city back. True once settled. */
    fun ease(shown: FloatArray, target: FloatArray, dt: Float): Boolean {
        val rise = 1f - exp(-dt / 90f)
        val fall = 1f - exp(-dt / 220f)
        var settled = true
        for (i in shown.indices) {
            val gap = target[i] - shown[i]
            if (abs(gap) < 0.004f) {
                shown[i] = target[i]
                continue
            }
            shown[i] += gap * if (gap > 0f) rise else fall
            settled = false
        }
        return settled
    }
}

/** WCAG's large text: 24 sp, or 18.66 sp in bold. Icons count as large too. */
internal fun largeText(style: TextStyle): Boolean {
    val size = style.fontSize
    if (!size.isSp) return false
    return size.value >= 24f || size.value >= 18.66f && (style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold
}

/** Where the page's lines sit, and the shade the canvas has made of them. */
internal class CityCanvasState(internal val view: View) {
    private class Mark(var bounds: Rect, var large: Boolean)

    private val marks = HashMap<Any, Mark>()

    var changes by mutableIntStateOf(0)
        private set

    /** The page is mostly text; the city behind it goes slightly out of focus. */
    var dense by mutableStateOf(false)
        internal set

    internal var shown = FloatArray(0)
    internal var frame by mutableIntStateOf(0)

    fun put(key: Any, bounds: Rect, large: Boolean) {
        val mark = marks[key]
        if (mark != null && mark.bounds == bounds && mark.large == large) return
        if (mark == null) marks[key] = Mark(bounds, large) else {
            mark.bounds = bounds
            mark.large = large
        }
        changes++
    }

    fun remove(key: Any) {
        if (marks.remove(key) != null) changes++
    }

    internal fun lines(area: Rect, shade: CityShade?, margin: Float): List<CityScrim.Line> = marks.values.mapNotNull { mark ->
        val bounds = mark.bounds.intersect(area).takeUnless { it.isEmpty } ?: return@mapNotNull null
        val local = bounds.translate(-area.left, -area.top)
        // Until the scene is measured, a line takes the deepest shade rather than guess.
        val need = shade?.need(local, area.size, mark.large) ?: CITY_SHADE_MAX
        CityScrim.Line(local.top - margin, local.bottom + margin, need.coerceIn(CITY_SHADE_MIN, CITY_SHADE_MAX))
    }
}

internal val LocalCityCanvas = staticCompositionLocalOf<CityCanvasState?> { null }

@Composable
internal fun rememberCityCanvas(): CityCanvasState {
    val view = LocalView.current
    return remember(view) { CityCanvasState(view) }
}

/** Tells the city's shade that a line of text, an icon or a field sits here. */
@Composable
internal fun Modifier.cityLine(large: Boolean = false): Modifier {
    val canvas = LocalCityCanvas.current
    val key = remember { Any() }
    // Popups, menus and dialogs draw in windows of their own, over the focus veil instead.
    val view = LocalView.current
    val own = canvas?.takeIf { it.view === view }
    DisposableEffect(own) { onDispose { own?.remove(key) } }
    return if (own != null) onGloballyPositioned { own.put(key, it.boundsInWindow(), large) } else this
}

/** The slight depth of field, Android 12 and later: behind a page full of rows, and more behind a popup. */
@Composable
internal fun rememberCityBlur(state: CityCanvasState, overlay: WallpaperOverlayState): State<Float> {
    val target = when {
        Build.VERSION.SDK_INT < 31 -> 0.dp
        overlay.windows.intValue > 0 -> FOCUS_BLUR
        state.dense && !CityWallpapers.lively -> DEPTH_BLUR
        else -> 0.dp
    }
    val px = with(LocalDensity.current) { target.toPx() }
    return animateFloatAsState(px, tween(480, easing = FastOutSlowInEasing), label = "cityBlur")
}

/**
 * Everything drawn over the city: the faint veil every page shares, the dashboard's scrims above and
 * below, the shade behind the page's lines, and the focus veil behind a see-through popup.
 */
@Composable
internal fun CityCanvas(state: CityCanvasState, overlay: WallpaperOverlayState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lively by animateFloatAsState(if (CityWallpapers.lively) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "cityVeil")
    val focus by animateFloatAsState(if (overlay.windows.intValue > 0) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "cityFocus")
    val scene = CityWallpapers.selected
    // Day in twentieths: a slow dusk remeasures the scene every few minutes, not at every reading.
    val daylight = CityLight.shown?.takeIf { it.scene == scene }?.let { (it.daylight * 20f).roundToInt() / 20f }
    val shade by produceState<CityShade?>(null, scene, daylight) {
        withContext(Dispatchers.Default) { CityShade.load(context, scene, daylight) }?.let { value = it }
    }
    var area by remember { mutableStateOf(Rect.Zero) }
    val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
    val navigationBar = WindowInsets.navigationBars.getBottom(density).toFloat()
    LaunchedEffect(state, area, shade, statusBar, navigationBar) {
        val bounds = area
        if (bounds.height <= 0f) return@LaunchedEffect
        val cell = with(density) { CELL.toPx() }
        val cells = ceil(bounds.height / cell).toInt()
        if (state.shown.size != cells) state.shown = FloatArray(cells)
        val profile = with(density) {
            { lines: List<CityScrim.Line> -> CityScrim.profile(lines, cells, cell, BRIDGE.toPx(), SOFTEN.toPx(), FEATHER.toPx()) }
        }
        val margin = with(density) { LINE_MARGIN.toPx() }
        // The system's own icons above and below count as lines too.
        val bars = listOfNotNull(
            Rect(0f, 0f, bounds.width, statusBar).takeUnless { it.isEmpty },
            Rect(0f, bounds.height - navigationBar, bounds.width, bounds.height).takeUnless { it.isEmpty },
        )
        var target = FloatArray(cells)
        var seen = Int.MIN_VALUE
        var settled = false
        var last = 0L
        while (true) {
            // The dashboard draws its own scrims; the shade waits there.
            if (settled) {
                snapshotFlow { if (CityWallpapers.lively) -1 else state.changes }.first { it != seen }
                last = 0L
            }
            withFrameNanos { now ->
                val key = if (CityWallpapers.lively) -1 else state.changes
                if (key != seen) {
                    seen = key
                    target = if (key == -1) FloatArray(cells) else profile(
                        state.lines(bounds, shade, margin) + bars.map { bar ->
                            CityScrim.Line(bar.top, bar.bottom, (shade?.need(bar, bounds.size, true) ?: CITY_SHADE_MAX).coerceAtMost(CITY_SHADE_MAX))
                        },
                    )
                }
                settled = CityScrim.ease(state.shown, target, if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceAtMost(64f))
                last = now
                state.frame++
                val covered = state.shown.count { it > 0.15f }.toFloat() / cells
                if (state.dense && covered < 0.5f || !state.dense && covered > 0.62f) state.dense = !state.dense
            }
        }
    }
    Canvas(modifier.onGloballyPositioned { area = it.boundsInWindow() }) {
        state.frame
        val night = Color(CITY_SHADE)
        // The faint veil every page shares with the dashboard: the city as painted, a touch towards night.
        drawRect(night.copy(alpha = CITY_WALLPAPER_LIVELY_VEIL))
        if (lively > 0f) {
            // The dashboard: shade behind the title above and the controls below.
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
        val shown = state.shown
        val reading = (1f - lively) * (1f - focus)
        if (reading > 0f && shown.any { it > 0f }) {
            val cell = CELL.toPx()
            val stops = Array(shown.size) { i -> ((i + 0.5f) * cell / size.height).coerceIn(0f, 1f) to night.copy(alpha = shown[i]) }
            drawRect(Brush.verticalGradient(*stops), alpha = reading)
        }
        if (focus > 0f) drawRect(night.copy(alpha = CITY_FOCUS_VEIL * focus))
    }
}
