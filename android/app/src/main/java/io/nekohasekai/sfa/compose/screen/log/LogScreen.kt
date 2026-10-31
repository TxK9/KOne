package io.nekohasekai.sfa.compose.screen.log

import androidx.compose.runtime.getValue
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import io.nekohasekai.sfa.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.compose.screen.connections.AppTraffic
import io.nekohasekai.sfa.selfuse.PairedProfile
import cc.txwd.kone.shared.ShareLinks
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

/** Raw service logs can contain node addresses and credentials. Only connection state is public. */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    serviceStatus: Status = Status.Stopped,
    showStartFab: Boolean = false,
    showStatusBar: Boolean = false,
    title: String? = null,
    viewModel: LogViewerViewModel? = null,
    showPause: Boolean = true,
    showClear: Boolean = true,
    showStatusInfo: Boolean = true,
    emptyMessage: String? = null,
    saveFilePrefix: String = "logs",
    onBack: (() -> Unit)? = null,
    live: io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState? = null,
    apps: List<AppTraffic> = emptyList(),
    onCloseConnections: (List<String>) -> Unit = {},
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.knine_connection_status)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
        )
    }
    // The state as the dashboard says it, a weather seal and a word, on the title's own left edge;
    // while connected, the node and the traffic going through it, then each app's connections, live.
    val connected = serviceStatus == Status.Started && live != null
    val opened = remember { mutableStateMapOf<String, Long>() }
    val selected = live?.profiles?.firstOrNull { it.id == live.selectedProfileId }
    val names = remember(selected?.name) {
        RouteNames(selected?.name, selected?.name?.let { mapOf(ShareLinks.NODE_TAG to it, PairedProfile.PROXY_TAG to it) } ?: emptyMap())
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(LocalScaffoldPadding.current),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item(key = "state") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.nekohasekai.sfa.compose.screen.dashboard.WeatherSeal(
                    when (serviceStatus) {
                        Status.Started -> io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather.Clear
                        Status.Starting, Status.Stopping -> io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather.Mist
                        Status.Stopped -> io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather.Rest
                    },
                    22.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    when (serviceStatus) {
                        Status.Started -> "Connected"
                        Status.Starting -> "Connecting"
                        Status.Stopping -> "Disconnecting"
                        Status.Stopped -> "Disconnected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (connected && live != null) {
                    Text(
                        io.nekohasekai.sfa.compose.screen.dashboard.rememberUptime(live.serviceStartTime),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.graphicsLayer { alpha = 0.75f },
                    )
                }
            }
        }
        item(key = "live") {
            androidx.compose.animation.AnimatedVisibility(
                visible = connected,
                enter = io.nekohasekai.sfa.compose.theme.KnineMotion.reveal(),
                exit = io.nekohasekai.sfa.compose.theme.KnineMotion.conceal(),
            ) {
                if (live != null) LiveTraffic(live)
            }
        }
        if (connected) {
            statusConnections(
                apps = apps,
                opened = opened,
                onToggle = { key -> if (opened.remove(key) == null) opened[key] = android.os.SystemClock.uptimeMillis() },
                names = names,
                onClose = onCloseConnections,
            )
        }
    }
}

/** The node in use, its speeds as large numbers over one moving ink trace, and the totals beneath. */
@Composable
private fun LiveTraffic(live: io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState) {
    val selected = live.profiles.firstOrNull { it.id == live.selectedProfileId }
    val via = selected?.let { io.nekohasekai.sfa.selfuse.NodeChain.via(it.id) }?.let { id -> live.profiles.firstOrNull { it.id == id } }
    Column(Modifier.padding(top = 22.dp)) {
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.nekohasekai.sfa.compose.util.FlagIcons.codeFor(selected.icon, selected.name)?.let {
                    io.nekohasekai.sfa.compose.util.FlagGlyph(it, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(selected.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            }
            if (via != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("through", style = MaterialTheme.typography.labelMedium, modifier = Modifier.graphicsLayer { alpha = 0.7f })
                    Spacer(Modifier.width(6.dp))
                    io.nekohasekai.sfa.compose.util.FlagIcons.codeFor(via.icon, via.name)?.let {
                        io.nekohasekai.sfa.compose.util.FlagGlyph(it, size = 14.dp)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(via.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp)) {
            Speed("Down", live.downlink, Modifier.weight(1f))
            Speed("Up", live.uplink, Modifier.weight(1f))
        }
        TrafficTrace(live.downlinkHistory, live.uplinkHistory, Modifier.fillMaxWidth().height(96.dp).padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Detail("Received", live.downlinkTotal, Modifier.weight(1f))
            Detail("Sent", live.uplinkTotal, Modifier.weight(1f))
            Detail("Connections", live.connectionsIn, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Speed(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.graphicsLayer { alpha = 0.7f })
        // Each new reading rolls in from below, so the numbers move with the traffic.
        androidx.compose.animation.AnimatedContent(
            targetState = value,
            transitionSpec = {
                (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { it / 2 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
                    (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { -it / 2 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
            },
            label = "speed",
        ) { shown ->
            Text(shown, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}

@Composable
private fun Detail(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.graphicsLayer { alpha = 0.7f }, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/** Download as a soft ink wash, upload as a fine line over it, both sliding as the seconds pass. */
@Composable
private fun TrafficTrace(down: List<Float>, up: List<Float>, modifier: Modifier) {
    val ink = androidx.compose.material3.LocalContentColor.current
    val accent = androidx.compose.ui.graphics.Color(io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather.Clear.pigment)
    val peak by androidx.compose.animation.core.animateFloatAsState(
        maxOf(1f, (down + up).maxOrNull() ?: 1f), androidx.compose.animation.core.tween(600), label = "tracePeak",
    )
    androidx.compose.foundation.Canvas(modifier) {
        fun path(values: List<Float>, close: Boolean) = androidx.compose.ui.graphics.Path().apply {
            if (values.size < 2) return@apply
            val step = size.width / (values.size - 1)
            fun y(v: Float) = size.height - (v / peak).coerceIn(0f, 1f) * size.height * 0.92f
            moveTo(0f, y(values[0]))
            for (i in 1 until values.size) {
                val x0 = (i - 1) * step; val x1 = i * step
                val mid = (x0 + x1) / 2
                cubicTo(mid, y(values[i - 1]), mid, y(values[i]), x1, y(values[i]))
            }
            if (close) { lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        }
        drawPath(
            path(down, true),
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to ink.copy(alpha = 0.28f), 1f to ink.copy(alpha = 0.02f)),
        )
        drawPath(path(down, false), ink.copy(alpha = 0.75f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()))
        drawPath(path(up, false), accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()))
        drawLine(ink.copy(alpha = 0.16f), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 0.7.dp.toPx())
    }
}
