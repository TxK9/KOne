package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.screen.dashboard.weatherInk
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/*
 * The network tests share one face, so switching tabs moves nothing but the words: a ring to
 * tap in the same place, one line under it, then rows on the tools' 24 dp gutter. A result is
 * its weather (a sun, mist, the moon); why it is so stays one tap away.
 */

internal val TestGutter = 24.dp

internal enum class RingPhase { Idle, Running, Done }

/**
 * The ring: breathing while idle, sweeping round while a test runs (or filled to [fill] when the
 * test knows its progress), and closed in [accent] once a finished test has a verdict.
 */
@Composable
internal fun TestRing(
    phase: RingPhase,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sweep: Boolean = true,
    fill: Float = 0f,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = LocalContentColor.current
    val shown by animateFloatAsState(fill, tween(600, easing = FastOutSlowInEasing), label = "ringFill")
    val motion = rememberInfiniteTransition(label = "ring")
    val spin by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val breath by motion.animateFloat(0.10f, 0.22f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    Box(
        modifier
            .size(236.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .graphicsLayer { alpha = if (enabled || phase == RingPhase.Running) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            val track = when {
                phase == RingPhase.Running -> 0.12f
                phase == RingPhase.Done && accent != null -> 0.10f
                else -> breath
            }
            drawArc(ink.copy(alpha = track), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            when {
                phase == RingPhase.Running && sweep -> drawArc(ink.copy(alpha = 0.85f), spin + 135f, 46f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                phase == RingPhase.Running && shown > 0f -> drawArc(ink, 135f, 270f * shown, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                phase == RingPhase.Done && accent != null -> drawArc(accent, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** The ring's word when it asks to be tapped: Start, Test again. */
@Composable
internal fun RingWord(text: String) {
    Text(text, fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp, textAlign = TextAlign.Center)
}

/** A live number in the ring, with its unit. */
@Composable
internal fun RingNumber(value: String, unit: String = "") {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 54.sp, fontWeight = FontWeight.Light, style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"))
        if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/** The small line in the ring under its number or glyph: what is happening, or what a tap does. */
@Composable
internal fun RingCaption(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.graphicsLayer { alpha = 0.7f })
}

/** The one line under the ring: through which node, or why a test cannot run. */
@Composable
internal fun RingLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = TestGutter).graphicsLayer { alpha = 0.75f },
    )
}

/** Results side by side under the ring, faint until there is something to show. */
@Composable
internal fun TestFigures(measured: Boolean, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TestGutter).graphicsLayer { alpha = if (measured) 1f else 0.45f },
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) { content() }
}

@Composable
internal fun TestFigure(title: String, value: String, unit: String = "") {
    TestFigure(title) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
        if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** A figure whose value is a verdict: its weather glyph and a word, on the numbers' baseline. */
@Composable
internal fun TestFigure(title: String, weather: NodeWeather?, word: String) {
    TestFigure(title) {
        if (weather != null) {
            WeatherGlyph(weather, size = 20.dp, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(word, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun TestFigure(title: String, value: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.graphicsLayer { alpha = 0.7f })
        Spacer(Modifier.size(6.dp))
        Row(Modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.Bottom) { value() }
    }
}

/**
 * A result row: the title on the left, its value and weather on the right, all on one line.
 * With a [detail], tapping the row opens it underneath; [open] starts it open.
 */
@Composable
internal fun TestRow(
    title: String,
    weather: NodeWeather?,
    value: String? = null,
    detail: String? = null,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val ink = LocalContentColor.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = detail != null,
                    onClickLabel = if (detail == null) null else stringResource(if (open) R.string.kone_row_hide_details else R.string.kone_row_show_details),
                    onClick = onToggle,
                )
                .padding(horizontal = TestGutter, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neither text is cut: the layout below stacks them rather than shorten either.
            TitleAndValue(
                title = { Text(title, style = MaterialTheme.typography.bodyLarge) },
                value = value?.let {
                    { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.75f }) }
                },
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.padding(start = 12.dp).size(20.dp), contentAlignment = Alignment.Center) {
                if (weather != null) WeatherGlyph(weather, size = 20.dp, ink = ink)
            }
        }
        AnimatedVisibility(open && detail != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            TestNote(detail.orEmpty(), Modifier.padding(bottom = 10.dp))
        }
    }
}

/**
 * A row's title on the start and its value on the end while both fit on one line. When they do
 * not (a long value, a narrow screen, large text), the value goes under the title, whole and
 * wrapping at the row's full width, so nothing is squeezed, clipped or ellipsized.
 */
@Composable
private fun TitleAndValue(title: @Composable () -> Unit, value: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    Layout(contents = listOf<@Composable () -> Unit>(title, value ?: {}), modifier = modifier) { (titles, values), constraints ->
        val gap = 12.dp.roundToPx()
        val titleMeasurable = titles.first()
        val valueMeasurable = values.firstOrNull()
        val titleNeed = titleMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueNeed = valueMeasurable?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val oneLine = titleNeed + if (valueMeasurable == null) 0 else gap + valueNeed
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else oneLine
        val loose = Constraints(maxWidth = width)
        val titlePlaced = titleMeasurable.measure(loose)
        val valuePlaced = valueMeasurable?.measure(loose)
        if (valuePlaced == null || oneLine <= width) {
            val height = maxOf(titlePlaced.height, valuePlaced?.height ?: 0)
            layout(width, height) {
                titlePlaced.placeRelative(0, (height - titlePlaced.height) / 2)
                valuePlaced?.placeRelative(width - valuePlaced.width, (height - valuePlaced.height) / 2)
            }
        } else {
            val between = 2.dp.roundToPx()
            layout(width, titlePlaced.height + between + valuePlaced.height) {
                titlePlaced.placeRelative(0, 0)
                valuePlaced.placeRelative(0, titlePlaced.height + between)
            }
        }
    }
}

/** A heading that opens what belongs under it, such as Wi-Fi Calling or the test's scope. */
@Composable
internal fun TestFold(title: String, open: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "fold")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = TestGutter, vertical = 13.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
    }
}

/** A row that leaves KOne for a system setting. */
@Composable
internal fun TestLink(title: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TestGutter, vertical = 13.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** Small print on the gutter: a reason, a caveat, the scope of a test. */
@Composable
internal fun TestNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth().padding(horizontal = TestGutter).graphicsLayer { alpha = 0.7f },
    )
}

/** The verdict colour a finished ring closes in. */
@Composable
internal fun ringAccent(weather: NodeWeather): Color = weatherInk(weather, LocalContentColor.current)
