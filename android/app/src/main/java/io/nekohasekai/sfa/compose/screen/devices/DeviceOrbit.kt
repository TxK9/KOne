package io.nekohasekai.sfa.compose.screen.devices

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherSeal
import io.nekohasekai.sfa.compose.screen.dashboard.weatherInk
import io.nekohasekai.sfa.compose.theme.knineSurface
import io.nekohasekai.sfa.devices.Availability
import io.nekohasekai.sfa.devices.DeviceRole
import io.nekohasekai.sfa.devices.EngineKind
import io.nekohasekai.sfa.devices.LinkState
import io.nekohasekai.sfa.devices.RunState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/*
 * The orrery. The device is a medallion at the centre with its weather seal stamped on the rim;
 * its engines ride a tilted ink orbit around it. Choosing an engine turns the orbit until that
 * engine stands at the front, nearest its controls below; tapping the medallion chooses the device
 * again. The orbit can also be turned by hand and settles on the engine nearest the front, with a
 * tick at each one it passes.
 *
 * Dashed lines are links nothing drives yet. Only measured traffic travels along the spokes, and
 * only a live link carries a moving light, so an unconnected orbit is perfectly still and costs
 * nothing between touches. Positions are read while laying out and drawing, never while composing,
 * so turning the orbit redraws without recomposing anything.
 */

private val HeroHeight = 290.dp
private val CenterY = 132.dp
private val BodySize = 46.dp
private val MedallionSize = 84.dp
private val OrbitDepth = 92.dp
private val OrbitReach = 150.dp
private val LabelGap = 7.dp
private val EdgeMargin = 10.dp

/** Screen degrees, y downward: 90 is the front of the orbit. */
private const val FRONT = 90f

/** With the device chosen, the engines rest on the diagonals and leave the medallion clear. */
private const val AT_REST = 45f

private val SunGlow = Color(0xFFD9652B)
private val MoonGlow = Color(0xFFE6E1D3)

/** Where an engine sits on the orbit before it is turned. */
internal fun orbitSeat(index: Int): Float = 90f + index * 90f

/** The shortest signed turn for an angle, between -180 and 180 degrees. */
internal fun wrapDegrees(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

private fun turnFor(kind: EngineKind?): Float = if (kind == null) AT_REST else FRONT - orbitSeat(kind.ordinal)

private fun frontmost(turn: Float): Int = EngineKind.entries.indices.minBy { abs(wrapDegrees(orbitSeat(it) + turn - FRONT)) }

@Composable
internal fun DeviceOrbit(
    overview: DeviceOverviewState,
    selected: EngineKind?,
    onSelect: (EngineKind?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalContentColor.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(turnFor(selected)) }
    var settle by remember { mutableIntStateOf(0) }
    val chosen by rememberUpdatedState(selected)
    val choose by rememberUpdatedState(onSelect)
    LaunchedEffect(selected, settle) {
        turn.animateTo(turn.value + wrapDegrees(turnFor(selected) - turn.value), spring(dampingRatio = 0.74f, stiffness = 200f))
    }
    val centred by animateFloatAsState(if (selected == null) 1f else 0f, tween(460, easing = FastOutSlowInEasing), label = "centred")
    val linked = overview.integration == Availability.Ready && overview.link == LinkState.Connected
    val flowing = overview.engines.any { row -> row.traffic?.let { it.uploadRate + it.downloadRate > 0 } == true }
    // The one clock that moves anything by itself, and it exists only while something is live.
    val phase = if (linked || flowing) {
        rememberInfiniteTransition(label = "orbit").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    } else {
        null
    }
    val glow = if (ink.luminance() > 0.5f) MoonGlow else SunGlow
    val (deviceWeather, deviceWord) = linkState(overview.integration, overview.link)

    BoxWithConstraints(modifier.fillMaxWidth().height(HeroHeight)) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val cx = width / 2f
        val cy = with(density) { CenterY.toPx() }
        val rx = min(width * 0.34f, with(density) { OrbitReach.toPx() })
        val ry = with(density) { OrbitDepth.toPx() }
        val bodyHalf = with(density) { BodySize.toPx() / 2f }
        val medallionHalf = with(density) { MedallionSize.toPx() / 2f }
        val labelGap = with(density) { LabelGap.toPx() }
        val margin = with(density) { EdgeMargin.toPx() }
        val centre = Offset(cx, cy)

        fun angle(index: Int, t: Float): Double = Math.toRadians((orbitSeat(index) + t).toDouble())
        fun place(index: Int, t: Float): Offset = angle(index, t).let { Offset(cx + rx * cos(it).toFloat(), cy + ry * sin(it).toFloat()) }

        // 0 at the back of the orbit, 1 at the front: nearer engines are larger and firmer.
        fun nearness(index: Int, t: Float): Float = (1f + sin(angle(index, t)).toFloat()) / 2f
        fun scaleAt(index: Int, t: Float): Float = 0.82f + 0.18f * nearness(index, t)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(rx) {
                    var dragged = 0f
                    var detent = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragged = turn.value
                            detent = frontmost(dragged)
                        },
                        onDragEnd = {
                            val kind = EngineKind.entries[frontmost(dragged)]
                            if (kind != chosen) choose(kind) else settle++
                        },
                        onDragCancel = { settle++ },
                    ) { change, amount ->
                        change.consume()
                        // A finger's travel along the front of the orbit turns it by the same arc.
                        dragged -= amount / (rx * PI.toFloat() / 180f)
                        val now = dragged
                        scope.launch { turn.snapTo(now) }
                        val front = frontmost(now)
                        if (front != detent) {
                            detent = front
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val t = turn.value
                val flow = phase?.value
                // Light behind what is chosen: the sun by day, the moon by night.
                if (centred > 0.01f) {
                    val reach = 124.dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.17f * centred), Color.Transparent), centre, reach), reach, centre)
                }
                selected?.let { kind ->
                    val strength = 1f - centred
                    if (strength > 0.01f) {
                        val at = place(kind.ordinal, t)
                        val reach = 74.dp.toPx()
                        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.17f * strength), Color.Transparent), at, reach), reach, at)
                    }
                }
                // The orbit, tilted: faint behind the medallion, firmer in front. Dashed until linked.
                drawOval(
                    Brush.verticalGradient(listOf(ink.copy(alpha = 0.06f), ink.copy(alpha = 0.3f)), startY = cy - ry, endY = cy + ry),
                    Offset(cx - rx, cy - ry),
                    Size(rx * 2f, ry * 2f),
                    style = Stroke(1.2.dp.toPx(), pathEffect = if (linked) null else dashes(1.5.dp.toPx(), 5.dp.toPx())),
                )
                // A wider, fainter ring, as an astrolabe has, gives the drawing its depth.
                drawOval(ink.copy(alpha = 0.035f), Offset(cx - rx * 1.16f, cy - ry * 1.16f), Size(rx * 2.32f, ry * 2.32f), style = Stroke(1f))
                // Spokes from the medallion to each engine; dashed while nothing drives it.
                overview.engines.forEachIndexed { index, row ->
                    val at = place(index, t)
                    val towards = (at - centre) / (at - centre).getDistance()
                    val from = centre + towards * (medallionHalf + 13.dp.toPx())
                    val to = at - towards * (bodyHalf * scaleAt(index, t) + 6.dp.toPx())
                    val firm = (0.06f + 0.15f * nearness(index, t)) * if (row.kind == selected) 1.8f else 1f
                    val driven = row.availability == Availability.Ready
                    drawLine(ink.copy(alpha = firm), from, to, strokeWidth = 1f, pathEffect = if (driven) null else dashes(2.dp.toPx(), 4.dp.toPx()))
                    // Measured traffic only: upload travels outward, download inward, faster when busier.
                    val sample = row.traffic
                    if (flow != null && sample != null) {
                        listOf(sample.uploadRate to 1f, sample.downloadRate to -1f).forEach { (bytes, way) ->
                            if (bytes <= 0L) return@forEach
                            val pace = 1f + log10(bytes.toFloat().coerceAtLeast(1f)) / 3f
                            repeat(2) { k ->
                                val run = (flow * pace + k * 0.5f) % 1f
                                val u = if (way > 0f) run else 1f - run
                                drawCircle(ink.copy(alpha = 0.6f * sin(PI.toFloat() * u)), 1.6.dp.toPx(), from + (to - from) * u)
                            }
                        }
                    }
                }
                // The chosen engine's halo, fading as the device is chosen again.
                selected?.let { kind ->
                    val at = place(kind.ordinal, t)
                    drawCircle(ink.copy(alpha = 0.34f * (1f - centred)), bodyHalf * scaleAt(kind.ordinal, t) + 5.dp.toPx(), at, style = Stroke(1f))
                }
                // The link around the medallion: dashed until an integration links the device, then a
                // light travels round it.
                val linkRadius = medallionHalf + 10.dp.toPx()
                drawCircle(ink.copy(alpha = 0.16f + 0.2f * centred), linkRadius, centre, style = Stroke(1f, pathEffect = if (linked) null else dashes(1.5.dp.toPx(), 4.dp.toPx())))
                if (linked && flow != null) {
                    drawArc(
                        ink.copy(alpha = 0.55f), flow * 360f, 34f, false,
                        Offset(cx - linkRadius, cy - linkRadius), Size(linkRadius * 2f, linkRadius * 2f),
                        style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            overview.engines.forEachIndexed { index, row ->
                OrbitBody(
                    row = row,
                    chosen = row.kind == selected,
                    half = bodyHalf,
                    centre = { place(index, turn.value) },
                    scale = { scaleAt(index, turn.value) },
                    nearness = { nearness(index, turn.value) },
                    onClick = { if (row.kind != selected) onSelect(row.kind) },
                )
                OrbitLabel(
                    text = row.kind.title,
                    chosen = row.kind == selected,
                    anchor = { place(index, turn.value) + Offset(0f, bodyHalf * scaleAt(index, turn.value) + labelGap) },
                    nearness = { nearness(index, turn.value) },
                    width = width,
                    margin = margin,
                )
            }

            Medallion(
                deviceIcon = if (overview.device.role == DeviceRole.ThisDevice) Icons.Outlined.PhoneAndroid else Icons.Outlined.DevicesOther,
                description = "${overview.device.name}, $deviceWord",
                weather = deviceWeather,
                chosen = selected == null,
                centre = centre,
                half = medallionHalf,
                onClick = { if (selected != null) onSelect(null) },
            )
        }
    }
}

/** An engine on the orbit: paper underneath so the orbit passes behind it, then its wash and rim. */
@Composable
private fun OrbitBody(
    row: EngineRowState,
    chosen: Boolean,
    half: Float,
    centre: () -> Offset,
    scale: () -> Float,
    nearness: () -> Float,
    onClick: () -> Unit,
) {
    val ink = LocalContentColor.current
    val paper = knineSurface()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = 650f), label = "bodyPress")
    val (_, word) = engineState(row.availability, row.status)
    val driven = row.availability == Availability.Ready
    val accent = when (row.status?.state) {
        RunState.Running -> weatherInk(NodeWeather.Clear, ink)
        RunState.Starting, RunState.Stopping -> weatherInk(NodeWeather.Mist, ink)
        RunState.Failed -> weatherInk(NodeWeather.Cut, ink)
        else -> null
    }
    Box(
        Modifier
            .offset { centre().let { IntOffset((it.x - half).roundToInt(), (it.y - half).roundToInt()) } }
            .size(BodySize)
            .graphicsLayer {
                val s = scale() * press
                scaleX = s
                scaleY = s
                alpha = if (chosen) 1f else 0.48f + 0.52f * nearness()
            }
            .clip(CircleShape)
            .semantics {
                contentDescription = "${row.kind.title}, $word"
                selected = chosen
            }
            .clickable(source, InkBleed, role = Role.Tab, onClick = onClick)
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(paper)
                drawCircle(ink.copy(alpha = if (driven) 0.08f else 0.04f))
                if (accent != null) {
                    drawCircle(accent, r - 1.dp.toPx(), style = Stroke(1.6.dp.toPx()))
                } else {
                    drawCircle(
                        ink.copy(alpha = if (chosen) 0.5f else 0.32f), r - 0.75.dp.toPx(),
                        style = Stroke(1f, pathEffect = if (driven) null else dashes(1.6.dp.toPx(), 3.2.dp.toPx())),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(row.kind.icon, contentDescription = null, modifier = Modifier.size(21.dp))
    }
}

/** An engine's name under it, kept whole inside the screen's edges. */
@Composable
private fun OrbitLabel(text: String, chosen: Boolean, anchor: () -> Offset, nearness: () -> Float, width: Float, margin: Float) {
    Text(
        text,
        style = if (chosen) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .layout { measurable, constraints ->
                val label = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(label.width, label.height) {
                    val at = anchor()
                    val x = (at.x - label.width / 2f).coerceIn(margin, (width - margin - label.width).coerceAtLeast(margin))
                    label.place(x.roundToInt(), at.y.roundToInt())
                }
            }
            .graphicsLayer { alpha = if (chosen) 1f else 0.42f + 0.43f * nearness() },
    )
}

/** The device: a paper medallion with a seal's double rim, its weather stamped at the lower right. */
@Composable
private fun Medallion(
    deviceIcon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    weather: NodeWeather,
    chosen: Boolean,
    centre: Offset,
    half: Float,
    onClick: () -> Unit,
) {
    val ink = LocalContentColor.current
    val paper = knineSurface()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "medallionPress")
    val firm by animateFloatAsState(if (chosen) 1f else 0f, tween(420), label = "medallionFirm")
    Box(
        Modifier
            .offset { IntOffset((centre.x - half).roundToInt(), (centre.y - half).roundToInt()) }
            .size(MedallionSize)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                selected = chosen
            }
            .clickable(source, InkBleed, role = Role.Tab, onClick = onClick)
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(paper)
                drawCircle(ink.copy(alpha = 0.035f + 0.03f * firm))
                drawCircle(ink.copy(alpha = 0.42f + 0.2f * firm), r - 0.8.dp.toPx(), style = Stroke(1.2.dp.toPx()))
                drawCircle(ink.copy(alpha = 0.14f), r - 5.dp.toPx(), style = Stroke(1f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(deviceIcon, contentDescription = null, modifier = Modifier.size(32.dp))
    }
    val sealHalf = with(LocalDensity.current) { 11.dp.toPx() }
    WeatherSeal(
        weather,
        size = 22.dp,
        modifier = Modifier.offset {
            IntOffset((centre.x + half * 0.66f - sealHalf).roundToInt(), (centre.y + half * 0.66f - sealHalf).roundToInt())
        },
    )
}
