package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.KnineArrive
import io.nekohasekai.sfa.compose.screen.dashboard.StatusDot
import io.nekohasekai.sfa.compose.screen.dashboard.rememberMotionOn
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.WallpaperGlass
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KnineStartAction(onClick: () -> Unit) {
    KnineConnectAction(Status.Stopped, onStart = onClick, onStop = {})
}

/**
 * [KnineStartAction]'s look for every service state. The photo dashboard passes
 * [glass] = false: no underlay at all, the label's glow alone keeps it readable.
 */
@Composable
fun KnineConnectAction(status: Status, onStart: () -> Unit, onStop: () -> Unit, glass: Boolean = true) {
    val label = when (status) {
        Status.Stopped -> stringResource(R.string.knine_connect)
        Status.Starting -> stringResource(R.string.status_starting)
        Status.Started -> stringResource(R.string.knine_disconnect)
        Status.Stopping -> stringResource(R.string.status_stopping)
    }
    BackdropContrastScope {
        WallpaperGlass(enabled = glass) {
            ExtendedFloatingActionButton(
                modifier = Modifier.semantics { contentDescription = label },
                onClick = {
                    when (status) {
                        Status.Stopped -> onStart()
                        Status.Started -> onStop()
                        else -> Unit
                    }
                },
                icon = { PowerSeal(status) },
                text = {
                    // The words change in place: the old ones lift away, the new settle from below,
                    // and the action widens or narrows to them without a jump.
                    AnimatedContent(
                        targetState = label,
                        transitionSpec = {
                            (slideInVertically(tween(320, easing = KnineArrive)) { it / 3 } + fadeIn(tween(240, delayMillis = 80))) togetherWith
                                (slideOutVertically(tween(200)) { -it / 3 } + fadeOut(tween(140))) using
                                SizeTransform(clip = false) { _, _ -> tween(320, easing = KnineArrive) }
                        },
                        label = "connectLabel",
                    ) { shown -> Text(shown, fontWeight = FontWeight.SemiBold, emphasized = true) }
                },
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            )
        }
    }
}

private const val SEAL_ALPHA = 0.42f

/**
 * The power glyph and its seal, a hairline ring in the glyph's ink. It breathes while the
 * connection is made or taken down, is pressed into place once the connection holds, and lifts
 * away when it stops. At rest there is no ring: the original ⏻ alone. With the phone's animations
 * off the ring simply shows or not.
 */
@Composable
private fun PowerSeal(status: Status) {
    val ink = LocalContentColor.current
    val moving = rememberMotionOn()
    val ringAlpha = remember { Animatable(if (status == Status.Started) SEAL_ALPHA else 0f) }
    val ringScale = remember { Animatable(1f) }
    LaunchedEffect(status, moving) {
        when (status) {
            Status.Starting, Status.Stopping -> {
                if (!moving) {
                    ringScale.snapTo(1f)
                    ringAlpha.snapTo(SEAL_ALPHA * 0.6f)
                    return@LaunchedEffect
                }
                while (true) {
                    coroutineScope {
                        launch { ringAlpha.animateTo(SEAL_ALPHA, tween(1000, easing = FastOutSlowInEasing)) }
                        ringScale.animateTo(1.08f, tween(1000, easing = FastOutSlowInEasing))
                    }
                    coroutineScope {
                        launch { ringAlpha.animateTo(0.12f, tween(1000, easing = FastOutSlowInEasing)) }
                        ringScale.animateTo(0.9f, tween(1000, easing = FastOutSlowInEasing))
                    }
                }
            }
            Status.Started -> {
                // Already pressed, as when the page comes back while connected: nothing to replay.
                if (ringAlpha.value >= SEAL_ALPHA && ringScale.value == 1f) return@LaunchedEffect
                ringScale.snapTo(1.4f)
                coroutineScope {
                    launch { ringAlpha.animateTo(SEAL_ALPHA, tween(420, easing = KnineArrive)) }
                    ringScale.animateTo(1f, tween(560, easing = KnineArrive))
                }
            }
            Status.Stopped -> {
                coroutineScope {
                    launch { ringAlpha.animateTo(0f, tween(360)) }
                    ringScale.animateTo(1.24f, tween(360, easing = KnineArrive))
                }
                ringScale.snapTo(1f)
            }
        }
    }
    Box(
        Modifier
            .size(20.dp)
            .drawBehind {
                val alpha = ringAlpha.value
                if (alpha > 0.005f) {
                    drawCircle(ink.copy(alpha = alpha), radius = 12.dp.toPx() * ringScale.value, style = Stroke(width = 1.dp.toPx()))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp), emphasized = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnineConnectionBar(
    visible: Boolean,
    serviceStatus: Status,
    startTime: Long?,
    groupsCount: Int,
    hasGroups: Boolean,
    onGroupsClick: () -> Unit,
    connectionsCount: Int,
    onConnectionsClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    homeStatus: String = "",
    homeStatusAlpha: Float = 1f,
    onHomeClick: () -> Unit = {},
    onHeightChanged: (Int) -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        BackdropContrastScope {
            WallpaperGlass {
                Column(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { onHeightChanged(it.height) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), thickness = 0.5.dp)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val started = serviceStatus == Status.Started
                        val statusLabel = when (serviceStatus) {
                            Status.Started -> stringResource(R.string.knine_proxy_started)
                            Status.Starting -> stringResource(R.string.status_starting)
                            Status.Stopping -> stringResource(R.string.status_stopping)
                            else -> ""
                        }
                        val connectionsLabel = stringResource(R.string.title_connections)
                        val groupsLabel = stringResource(R.string.title_groups)
                        val stopLabel = stringResource(R.string.knine_disconnect)
                        val density = LocalDensity.current
                        val textMeasurer = rememberTextMeasurer()
                        val labelStyle = MaterialTheme.typography.labelSmall
                        val statusStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        val valueStyle = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)
                        fun metricWidth(count: Int, label: String): Int = maxOf(
                            with(density) { 48.dp.roundToPx() },
                            maxOf(
                                textMeasurer.measure(count.toString(), valueStyle).size.width,
                                textMeasurer.measure(label, labelStyle).size.width,
                            ) + with(density) { 12.dp.roundToPx() },
                        )
                        val stopWidth = maxOf(
                            with(density) { 48.dp.roundToPx() },
                            textMeasurer.measure(stopLabel, labelStyle).size.width + with(density) { 16.dp.roundToPx() },
                        )
                        val controlsWidth = stopWidth + if (started) {
                            metricWidth(connectionsCount, connectionsLabel) + with(density) { 2.dp.roundToPx() } +
                                if (hasGroups) metricWidth(groupsCount, groupsLabel) + with(density) { 2.dp.roundToPx() } else 0
                        } else 0
                        val statusWidth = textMeasurer.measure(statusLabel, statusStyle).size.width + with(density) { 14.dp.roundToPx() }
                        // Keep the clock and service state intact. The secondary
                        // home label may ellipsize, while TalkBack retains its text.
                        val detailWidth = if (startTime != null) {
                            textMeasurer.measure("00:00:00", labelStyle.copy(fontFeatureSettings = "tnum")).size.width +
                                if (homeStatus.isNotBlank()) with(density) { 44.dp.roundToPx() } else 0
                        } else 0
                        val summaryWidth = maxOf(statusWidth, detailWidth)
                        val compact = summaryWidth + controlsWidth + with(density) { 8.dp.roundToPx() } <= with(density) { maxWidth.roundToPx() }

                        Column {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ConnectionSummary(
                                    statusLabel = statusLabel,
                                    started = started,
                                    homeStatus = homeStatus,
                                    homeStatusAlpha = homeStatusAlpha,
                                    onHomeClick = onHomeClick,
                                    startTime = startTime,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (compact && started) {
                                        ConnectionMetric(connectionsCount, connectionsLabel, onConnectionsClick)
                                        if (hasGroups) ConnectionMetric(groupsCount, groupsLabel, onGroupsClick)
                                    }
                                    StopConnectionAction(stopLabel, serviceStatus != Status.Stopping, onStopClick)
                                }
                            }
                            if (!compact && started) {
                                // Large text and long localized labels get their own
                                // row instead of smaller fonts or clipped controls.
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    ConnectionMetric(connectionsCount, connectionsLabel, onConnectionsClick)
                                    if (hasGroups) ConnectionMetric(groupsCount, groupsLabel, onGroupsClick)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionSummary(statusLabel: String, started: Boolean, homeStatus: String, homeStatusAlpha: Float, onHomeClick: () -> Unit, startTime: Long?, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The dashboard's own dot: it breathes while connecting and pulses once connected.
            StatusDot(started = started, busy = !started && statusLabel.isNotEmpty())
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = statusLabel,
                transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
                label = "barStatus",
            ) { shown ->
                Text(
                    text = shown,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    emphasized = started,
                )
            }
        }
        if (homeStatus.isNotBlank() || startTime != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (homeStatus.isNotBlank()) {
                    IconButton(onClick = onHomeClick, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Home, contentDescription = homeStatus, modifier = Modifier.size(18.dp).alpha(homeStatusAlpha))
                    }
                }
                if (startTime != null) StableUptimeText(startTime)
            }
        }
    }
}

@Composable
private fun ConnectionMetric(count: Int, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.clearAndSetSemantics { contentDescription = "$count $label" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun StopConnectionAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
}

/** Milliseconds until the uptime clock shows its next second, so it neither drifts nor skips one. */
internal fun untilNextSecond(now: Long, startTime: Long): Long = 1_000 - Math.floorMod(now - startTime, 1_000L)

@Composable
private fun StableUptimeText(startTime: Long) {
    var currentTime by remember(startTime) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(startTime, lifecycle) {
        // Only while KOne is in view: in the background a once-a-second timer wakes it for nothing.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentTime = System.currentTimeMillis()
                delay(untilNextSecond(currentTime, startTime))
            }
        }
    }
    val elapsed = ((currentTime - startTime) / 1_000).coerceAtLeast(0)
    val hours = elapsed / 3_600
    val minutes = elapsed % 3_600 / 60
    val seconds = elapsed % 60
    val clock = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    Text(
        text = clock,
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Running for $hours h $minutes m $seconds s" },
    )
}
