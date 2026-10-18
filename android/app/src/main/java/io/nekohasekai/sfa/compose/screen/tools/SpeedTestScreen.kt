package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog

/**
 * Speed test: one ring to tap. It measures latency, then download, then upload through the node in
 * use (or directly when not connected), with the core's own network quality test; the address,
 * protocol and run time that the original tool asked for stay at their defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
    viewModel: NetworkQualityViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val connected = if (remoteServer != null) remoteConnected else serviceStatus == Status.Started
    val context = LocalContext.current

    OverrideTopBar {
        TopAppBar(
            title = { Text("Speed test") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }

    // One measurement after another reads more clearly than download and upload at once.
    LaunchedEffect(Unit) { viewModel.setSerial(true) }
    LaunchedEffect(connected) { if (!connected) viewModel.onVpnDisconnected() }
    DisposableEffect(Unit) { onDispose { if (viewModel.uiState.value.isRunning) viewModel.cancelTest() } }

    val route by produceState("Direct", connected) {
        value = if (!connected) {
            "Disconnected · Test the local network directly"
        } else {
            withContext(Dispatchers.IO) { ProfileManager.get(Settings.selectedProfile)?.name }?.let { "Via $it" } ?: "Via current node"
        }
    }

    if (state.showMeteredWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMeteredWarning() },
            title = { Text("Uses mobile data") },
            text = { Text("This speed test may use several hundred MB of data. Continue?") },
            confirmButton = { TextButton(onClick = { viewModel.confirmMeteredStart(connected) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissMeteredWarning() }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    val scaffold = LocalScaffoldPadding.current
    BackdropContrastScope(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = scaffold.calculateTopPadding() + 24.dp, bottom = scaffold.calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Gauge(state) {
                if (state.isRunning) viewModel.cancelTest() else viewModel.requestStartTest(context, connected)
            }
            Spacer(Modifier.height(18.dp))
            Text(route, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.75f })
            Spacer(Modifier.height(36.dp))
            Results(state)
        }
    }
}

private enum class Stage(val label: String) { Ready("Start"), Latency("Test latency"), Download("Downloading"), Upload("Uploading"), Done("Test again") }

private fun stageOf(state: NetworkQualityState): Stage = when {
    !state.isRunning && state.phase == Libbox.NetworkQualityPhaseDone.toInt() -> Stage.Done
    !state.isRunning -> Stage.Ready
    state.phase == Libbox.NetworkQualityPhaseDownload.toInt() -> Stage.Download
    state.phase == Libbox.NetworkQualityPhaseUpload.toInt() -> Stage.Upload
    else -> Stage.Latency
}

/** Megabits per second, as the number people compare. */
private fun mbps(bitsPerSecond: Long): String {
    val value = bitsPerSecond / 1_000_000.0
    return when {
        value <= 0.0 -> "—"
        value < 10 -> String.format("%.1f", value)
        else -> String.format("%.0f", value)
    }
}

/**
 * The ring: still and breathing before a test, sweeping round while latency is measured, and
 * filled to the live speed (on a logarithmic scale up to 1 Gbps) while data moves.
 */
@Composable
private fun Gauge(state: NetworkQualityState, onTap: () -> Unit) {
    val ink = LocalContentColor.current
    val stage = stageOf(state)
    val live = when (stage) {
        Stage.Download -> state.downloadCapacity
        Stage.Upload -> state.uploadCapacity
        else -> 0L
    }
    val target = if (live > 0) (ln(1.0 + live / 1_000_000.0) / ln(1001.0)).toFloat().coerceIn(0f, 1f) else 0f
    val fill by animateFloatAsState(target, tween(600, easing = FastOutSlowInEasing), label = "speedFill")
    val motion = rememberInfiniteTransition(label = "gauge")
    val spin by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val breath by motion.animateFloat(0.10f, 0.22f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")

    Box(
        Modifier
            .size(236.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(ink.copy(alpha = if (stage == Stage.Ready || stage == Stage.Done) breath else 0.12f), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            when (stage) {
                Stage.Latency -> drawArc(ink.copy(alpha = 0.85f), spin + 135f, 46f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                Stage.Download, Stage.Upload -> if (fill > 0f) drawArc(ink, 135f, 270f * fill, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                else -> Unit
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(targetState = stage, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "stage") { shown ->
                when (shown) {
                    Stage.Download, Stage.Upload -> Row(verticalAlignment = Alignment.Bottom) {
                        Text(mbps(live), fontSize = 54.sp, fontWeight = FontWeight.Light, style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"))
                        Text(" Mbps", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Stage.Latency -> Text(if (state.idleLatencyMs > 0) "${state.idleLatencyMs}" else "…", fontSize = 54.sp, fontWeight = FontWeight.Light)
                    else -> Text(shown.label, fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
                }
            }
            if (stage == Stage.Download || stage == Stage.Upload || stage == Stage.Latency) {
                Text(stage.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.graphicsLayer { alpha = 0.7f })
            }
        }
    }
}

/** Download, upload and latency side by side, and how responsive the line stays under load. */
@Composable
private fun Results(state: NetworkQualityState) {
    val measured = state.downloadCapacity > 0 || state.uploadCapacity > 0 || state.idleLatencyMs > 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).graphicsLayer { alpha = if (measured) 1f else 0.45f }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Figure("Download", mbps(state.downloadCapacity), "Mbps")
            Figure("Upload", mbps(state.uploadCapacity), "Mbps")
            Figure("Latency", if (state.idleLatencyMs > 0) "${state.idleLatencyMs}" else "—", "ms")
        }
        val rpm = maxOf(state.downloadRPM, state.uploadRPM)
        if (rpm > 0 && !state.isRunning) {
            Spacer(Modifier.height(22.dp))
            // Apple's networkQuality scale: under 300 round trips a minute under load is low, over 1000 high.
            val verdict = when {
                rpm >= 1000 -> "Responsive under load"
                rpm >= 300 -> "Slightly slower under load"
                else -> "Much slower under load"
            }
            Text(verdict, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 0.8f })
        }
    }
}

@Composable
private fun Figure(title: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.graphicsLayer { alpha = 0.7f })
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
            Text(" $unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
