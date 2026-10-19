package cc.txwd.kone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.txwd.kone.ink.InkGallery

/** How thick the fog lies over the bridge for each weather, as the preview has it. */
private class Fog(val drift: Float, val blanket: Float, val millis: Int)

private fun fogOf(weather: NodeWeather) = when (weather) {
    NodeWeather.Clear -> Fog(0.22f, 0f, 60_000)
    NodeWeather.Mist -> Fog(0.8f, 0.16f, 34_000)
    NodeWeather.Cut -> Fog(0.96f, 0.6f, 22_000)
    NodeWeather.Rest -> Fog(0f, 0f, 60_000)
}

/**
 * The tray panel, opened from the KOne icon by the clock: the Golden Gate above, under fog that
 * follows the connection (thin when S, thick when M, all but hiding it when !, grey when Z),
 * and below it the node, its delay and traffic, and Disconnect / Open KOne / Quit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrayPanel(state: DashboardState, onPower: () -> Unit, onOpen: () -> Unit, onQuit: () -> Unit, moving: Boolean = true) {
    val ink = LocalInk.current
    val node = state.current
    val online = state.connection == Connection.Online
    val weather = if (online && node != null) node.weather else NodeWeather.Rest
    Column(Modifier.fillMaxSize().background(ink.panel).border(1.dp, ink.hair)) {
        Sky(weather, moving)
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Flag(node?.country, 26.dp, 18.dp)
                Label(node?.name ?: "KOne", 17.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (online && node != null) Label(delayText(node.delay), 16.sp, weight = FontWeight.SemiBold, tabular = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WeatherGlyph(weather)
                    Label(
                        "${weather.char} · ${if (state.connection == Connection.Connecting) "Connecting" else weather.word}",
                        13.sp,
                    )
                }
                if (online) {
                    Label("↓ ${rate(state.downKbps)}　↑ ${rate(state.upKbps)}", 13.sp, ink.soft, tabular = true)
                    Label(clock(state.seconds), 13.sp, ink.soft, tabular = true)
                    if (state.home) Label("Home", 13.sp, ink.soft)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shape = RoundedCornerShape(22.dp)
                Row(
                    Modifier.height(44.dp).clip(shape).background(ink.ink, shape).press(onDark = true, onClick = onPower)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Glyph(Glyphs.power, ink.panel, 18.dp, strokeWidth = 2.1f)
                    Label(
                        when (state.connection) {
                            Connection.Online -> "Disconnect"
                            Connection.Connecting -> "Connecting"
                            Connection.Idle -> "Connect"
                        },
                        15.sp, ink.panel, FontWeight.SemiBold,
                    )
                }
                Box(Modifier.height(44.dp).press(onClick = onOpen).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Label("Open KOne", 14.sp, weight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.height(44.dp).press(onClick = onQuit).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Label("Quit", 13.sp, ink.soft)
                }
            }
        }
    }
}

@Composable
private fun Sky(weather: NodeWeather, moving: Boolean) {
    val ink = LocalInk.current
    var bridge by remember(ink.dark) { mutableStateOf<ImageBitmap?>(InkGallery.readyBridge(ink.dark)?.toComposeImageBitmap()) }
    LaunchedEffect(ink.dark) { if (bridge == null) bridge = InkGallery.bridge(ink.dark).toComposeImageBitmap() }
    val fog = fogOf(weather)
    val fogColor = if (ink.dark) Color(0xFF96A2A8) else Color(0xFFF0F2F5)
    // The fog drifts a few pixels a second: fifteen steps a second, not a frame at the screen's rate.
    val clock = rememberClock(moving) { 15 }
    val shift = if (moving) ((clock.value * 1000) % fog.millis / fog.millis).toFloat() else 0f
    Box(Modifier.fillMaxWidth().height(176.dp).clipToBounds().background(ink.paper)) {
        bridge?.let { image ->
            val rest = weather == NodeWeather.Rest
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / 360f
                drawImage(
                    image, dstOffset = IntOffset(0, (-150 * k).toInt()), dstSize = IntSize(size.width.toInt(), (480 * k).toInt()),
                    filterQuality = FilterQuality.Medium, alpha = if (rest) 0.42f else 1f,
                    colorFilter = if (rest) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                )
            }
        }
        // Banks of fog drifting across, a strip twice the width sliding by one width.
        if (fog.drift > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val banks = listOf(Triple(0.08f, 0.62f, 0.95f), Triple(0.27f, 0.42f, 0.85f), Triple(0.41f, 0.70f, 0.95f), Triple(0.58f, 0.62f, 0.95f), Triple(0.77f, 0.42f, 0.85f), Triple(0.91f, 0.70f, 0.95f))
                for ((x, y, strength) in banks) {
                    val cx = x * 2 * w - shift * w
                    val cy = y * h
                    val rx = 0.22f * 2 * w
                    val ry = 0.48f * h
                    scale(1f, ry / rx, pivot = Offset(cx, cy)) {
                        drawCircle(
                            Brush.radialGradient(0f to fogColor.copy(alpha = strength * fog.drift), 0.72f to fogColor.copy(alpha = 0f), center = Offset(cx, cy), radius = rx),
                            radius = rx, center = Offset(cx, cy),
                        )
                    }
                }
                if (fog.blanket > 0f) drawRect(fogColor.copy(alpha = fog.blanket), Offset.Zero, Size(w, h))
            }
        }
        Box(Modifier.padding(14.dp)) { WeatherSeal(weather, size = 40.dp, corner = 6.dp, fontSize = 23f, rim = 2.dp) }
    }
}
