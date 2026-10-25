package io.nekohasekai.sfa.compose.screen.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.screen.tools.TestGutter
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.devices.TrafficSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/*
 * The brushes device management paints with. Everything is drawn in the page's own ink, so the
 * same strokes read on paper, on night ink and over a wallpaper. Lines are one physical pixel; a
 * dashed line always means "linked to nothing yet". Nothing here animates unless it was touched or
 * was given a new measurement.
 */

/** Hairlines: the finest line the screen can draw, at a whisper of ink. */
internal const val HairAlpha = 0.12f

/** A press spreads like a drop of ink on rice paper, then dries; it keeps spreading after a quick tap. */
internal object InkBleed : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = InkBleedNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x1A7B1EED
}

private class InkBleedNode(private val source: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val spread = Animatable(0f)
    private val wet = Animatable(0f)
    private var origin = Offset.Unspecified
    private var spreading: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        origin = interaction.pressPosition
                        spreading?.cancel()
                        spreading = coroutineScope.launch {
                            wet.snapTo(0.13f)
                            spread.snapTo(0.08f)
                            invalidateDraw()
                            spread.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> coroutineScope.launch {
                        wet.animateTo(0f, tween(460, easing = LinearOutSlowInEasing))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = wet.value
        if (alpha <= 0f || !origin.isSpecified) return
        val ink = currentValueOf(LocalContentColor)
        val reach = hypot(size.width, size.height)
        clipRect { drawCircle(ink.copy(alpha = alpha), radius = reach * spread.value, center = origin) }
    }
}

internal fun dashes(on: Float, off: Float): PathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off), 0f)

/** Small capitals with air between the letters: how sections are named. */
@Composable
internal fun SmallCaps(text: String, modifier: Modifier = Modifier, alpha: Float = 0.55f) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.6.sp,
        maxLines = 1,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/** A section's name in small capitals, then a hairline running to the edge, then an optional note. */
@Composable
internal fun SectionRule(title: String, note: String? = null) {
    val ink = LocalContentColor.current
    Row(
        Modifier.fillMaxWidth().padding(start = TestGutter, end = TestGutter, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallCaps(title)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .height(1.dp)
                .drawBehind { drawLine(ink.copy(alpha = HairAlpha), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f) },
        )
        if (note != null) SmallCaps(note, alpha = 0.45f)
    }
}

/** A quiet line of small print, centred under what it explains. */
@Composable
internal fun Whisper(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = TestGutter * 2).graphicsLayer { alpha = 0.6f },
    )
}

// ---- Stations: a few states side by side, divided by hairlines ---------------------------------

internal data class Station(val label: String, val word: String, val weather: NodeWeather?, val live: Boolean)

@Composable
internal fun Stations(stations: List<Station>) {
    val ink = LocalContentColor.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = TestGutter / 2)) {
        stations.forEachIndexed { index, station ->
            if (index > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).drawBehind {
                    drawLine(ink.copy(alpha = HairAlpha), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1f)
                })
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp).graphicsLayer { alpha = if (station.live) 1f else 0.62f },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    station.weather?.let { WeatherGlyph(it, size = 20.dp) }
                }
                Spacer(Modifier.height(6.dp))
                Text(station.word, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(3.dp))
                SmallCaps(station.label, alpha = 0.5f)
            }
        }
    }
}

// ---- Spec sheet: facts in two columns ----------------------------------------------------------

@Composable
internal fun SpecSheet(facts: List<Pair<String, String>>) {
    val ink = LocalContentColor.current
    Column(Modifier.fillMaxWidth().padding(horizontal = TestGutter)) {
        facts.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .drawBehind {
                        if (rowIndex > 0) drawLine(ink.copy(alpha = HairAlpha), Offset.Zero, Offset(size.width, 0f), strokeWidth = 1f)
                        if (pair.size == 2) drawLine(ink.copy(alpha = HairAlpha), Offset(size.width / 2, 12.dp.toPx()), Offset(size.width / 2, size.height - 12.dp.toPx()), strokeWidth = 1f)
                    },
            ) {
                pair.forEachIndexed { column, (label, value) ->
                    Column(Modifier.weight(1f).padding(start = if (column == 0) 0.dp else 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
                        SmallCaps(label, alpha = 0.5f)
                        Spacer(Modifier.height(4.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---- Ink stones: the engine's power, as three round stones ------------------------------------

/**
 * A round control like a stone on a go board. Ready, it is a soft ink wash with a firm rim; inert, it
 * is only a dashed outline, so it never looks pressable when it is not.
 */
@Composable
internal fun InkStone(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val ink = LocalContentColor.current
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = 650f), label = "stonePress")
    val rim by animateFloatAsState(if (!enabled) 0f else if (emphasized) 1f else 0.5f, tween(260), label = "stoneRim")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .clip(CircleShape)
                .clickable(source, InkBleed, enabled = enabled, role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
                .drawBehind {
                    val r = size.minDimension / 2
                    if (enabled) {
                        drawCircle(ink.copy(alpha = 0.05f + 0.06f * rim))
                        drawCircle(ink.copy(alpha = 0.18f + 0.3f * rim), r - 0.5f, style = Stroke(1f))
                    } else {
                        drawCircle(ink.copy(alpha = 0.3f), r - 1.dp.toPx(), style = Stroke(1.dp.toPx(), pathEffect = dashes(2.dp.toPx(), 3.6.dp.toPx())))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).graphicsLayer { alpha = if (enabled) 1f else 0.32f })
        }
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = if (enabled) 0.8f else 0.36f })
    }
}

/** A plain line with a switch, the switch showing only what was reported. */
@Composable
internal fun InkSwitchRow(title: String, checked: Boolean?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val live = enabled && checked != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(source, InkBleed, enabled = live, role = Role.Switch) { checked?.let { onChange(!it) } }
            .padding(horizontal = TestGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f).graphicsLayer { alpha = if (live) 1f else DimAlpha })
        KnineSwitch(checked = checked == true, onCheckedChange = null, enabled = live)
    }
}

/** A text action in ink, for the small things inside a fold. */
@Composable
internal fun QuietAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .padding(horizontal = TestGutter - 10.dp, vertical = 4.dp)
            .clip(CircleShape)
            .clickable(source, InkBleed, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 0.85f else 0.34f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

// ---- The brush: choosing one word of a few -----------------------------------------------------

/**
 * Words spread across the line with a brush stroke under the chosen one. Moving, the stroke's
 * leading edge runs ahead and the trailing edge follows, so it stretches like wet ink and settles.
 * With nothing chosen there is no stroke at all, only a dotted line waiting for one.
 */
@Composable
internal fun BrushSelector(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    optionEnabled: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = LocalContentColor.current
    val haptic = LocalHapticFeedback.current
    val spans = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val start = remember { Animatable(0f) }
    val end = remember { Animatable(0f) }
    var inked by remember { mutableStateOf(false) }
    val target = spans[selected]
    LaunchedEffect(target) {
        val (left, right) = target ?: run { inked = false; return@LaunchedEffect }
        if (!inked) {
            start.snapTo(left)
            end.snapTo(right)
            inked = true
            return@LaunchedEffect
        }
        val rightward = left > start.value
        val lead = spring<Float>(dampingRatio = 0.82f, stiffness = 900f)
        val trail = spring<Float>(dampingRatio = 0.86f, stiffness = 230f)
        coroutineScope {
            launch { start.animateTo(left, if (rightward) trail else lead) }
            launch { end.animateTo(right, if (rightward) lead else trail) }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = TestGutter / 2)
            .drawBehind {
                val y = size.height - 11.dp.toPx()
                if (!inked || target == null) {
                    // Waiting: a dotted baseline the whole width.
                    val step = 5.dp.toPx()
                    var x = 12.dp.toPx()
                    while (x < size.width - 12.dp.toPx()) {
                        drawCircle(ink.copy(alpha = 0.2f), radius = 0.8.dp.toPx(), center = Offset(x, y))
                        x += step
                    }
                } else {
                    drawPath(brushStroke(start.value, end.value, y, 1.7.dp.toPx()), ink.copy(alpha = 0.92f))
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, label ->
                val enabled = optionEnabled(index)
                val chosen = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { box ->
                            val inset = box.size.width * 0.22f
                            spans[index] = (box.positionInParent().x + inset) to (box.positionInParent().x + box.size.width - inset)
                        }
                        .clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, InkBleed, enabled = enabled && !chosen, role = Role.RadioButton) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 8.dp).graphicsLayer { alpha = if (chosen) 1f else if (enabled) 0.58f else 0.3f },
                    )
                }
            }
        }
    }
}

/** A calligraphic stroke from [x0] to [x1]: pressed at the start, thinning, lifted at the end. */
private fun brushStroke(x0: Float, x1: Float, y: Float, weight: Float): Path {
    val length = (x1 - x0).coerceAtLeast(1f)
    val press = weight * 1.15f
    val lift = weight * 0.28f
    return Path().apply {
        moveTo(x0, y - press)
        cubicTo(x0 + length * 0.3f, y - press * 1.08f, x0 + length * 0.72f, y - weight * 0.82f, x1, y - lift)
        quadraticTo(x1 + lift * 2f, y, x1, y + lift)
        cubicTo(x0 + length * 0.72f, y + weight * 0.9f, x0 + length * 0.3f, y + press, x0, y + press)
        quadraticTo(x0 - press * 1.25f, y, x0, y - press)
        close()
    }
}

// ---- The waterline: traffic as a mountain and its reflection -----------------------------------

/**
 * Upload rises above the waterline and download is its reflection below, the way a mountain stands
 * over still water. Without measurements the water is simply still. Each new sample glides in from
 * the right over the next second; nothing moves between samples.
 */
@Composable
internal fun Waterline(history: List<TrafficSnapshot>, latest: TrafficSnapshot?, slots: Int, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    val glide = remember { Animatable(0f) }
    LaunchedEffect(history.lastOrNull()?.sampledAt) {
        if (history.size >= 2) {
            glide.snapTo(1f)
            glide.animateTo(0f, tween(900, easing = LinearEasing))
        }
    }
    Box(modifier.fillMaxWidth().height(96.dp).padding(horizontal = TestGutter)) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val mid = size.height / 2
            drawLine(ink.copy(alpha = 0.24f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
            if (history.size < 2) return@Canvas
            val peak = max(history.maxOf { max(it.uploadRate, it.downloadRate) }.toFloat(), RATE_FLOOR)
            val reach = mid - 8.dp.toPx()
            val step = size.width / (slots - 1).coerceAtLeast(1)
            fun x(index: Int) = size.width - (history.size - 1 - index + glide.value) * step
            fun ridge(rate: (TrafficSnapshot) -> Long, sign: Float): List<Offset> =
                history.mapIndexed { index, sample -> Offset(x(index), mid - sign * reach * (rate(sample) / peak).coerceIn(0f, 1f)) }
            clipRect {
                listOf(ridge({ it.uploadRate }, 1f) to 1f, ridge({ it.downloadRate }, -1f) to -1f).forEach { (points, sign) ->
                    val line = smooth(points)
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(points.last().x, mid)
                        lineTo(points.first().x, mid)
                        close()
                    }
                    drawPath(fill, ink.copy(alpha = if (sign > 0) 0.10f else 0.05f))
                    drawPath(line, ink.copy(alpha = if (sign > 0) 0.62f else 0.32f), style = Stroke(if (sign > 0) 1.3.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawCircle(ink.copy(alpha = if (sign > 0) 0.85f else 0.45f), radius = 2.dp.toPx(), center = points.last())
                }
            }
        }
        Text(
            "↑ " + rate(latest?.uploadRate),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.align(Alignment.TopStart).graphicsLayer { alpha = if (latest != null) 0.85f else 0.4f },
        )
        Text(
            "↓ " + rate(latest?.downloadRate),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = if (latest != null) 0.7f else 0.4f },
        )
    }
}

/** Below a kilobyte a second, a chart would only draw noise as mountains. */
private const val RATE_FLOOR = 1024f

/** A line through the points, softened by curving to the midpoints between them. */
private fun smooth(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size - 1) {
        val here = points[i]
        val next = points[i + 1]
        quadraticTo(here.x, here.y, (here.x + next.x) / 2, (here.y + next.y) / 2)
    }
    lineTo(points.last().x, points.last().y)
}

// ---- Folds ------------------------------------------------------------------------------------

/** A heading that opens what belongs under it; its state word sits just before the chevron. */
@Composable
internal fun InkFold(title: String, state: String?, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val ink = LocalContentColor.current
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 420f), label = "fold")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(ink.copy(alpha = HairAlpha), Offset(TestGutter.toPx(), 0f), Offset(size.width - TestGutter.toPx(), 0f), strokeWidth = 1f) }
                .clickable(remember { MutableInteractionSource() }, InkBleed, role = Role.Button, onClick = onToggle)
                .padding(horizontal = TestGutter, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
            if (state != null) {
                Text(state, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp).graphicsLayer { alpha = 0.55f })
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn; alpha = 0.6f })
        }
        AnimatedVisibility(
            open,
            enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 380f)),
            exit = fadeOut(tween(140)) + shrinkVertically(spring(dampingRatio = 0.95f, stiffness = 420f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.Top, content = content)
        }
    }
}
