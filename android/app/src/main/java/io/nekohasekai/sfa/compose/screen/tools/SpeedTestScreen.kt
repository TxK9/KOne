package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog

/**
 * Network speed test: one ring to tap. It measures latency, then download, then upload through the node in
 * use (or directly when not connected), with the core's own network quality test; the address,
 * protocol and run time that the original tool asked for stay at their defaults.
 */
@Composable
internal fun SpeedTestScreen(
    serviceStatus: Status = Status.Stopped,
    viewModel: NetworkQualityViewModel,
    enabled: Boolean = true,
) {
    val state by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val connected = if (remoteServer != null) remoteConnected else serviceStatus == Status.Started
    val context = LocalContext.current

    // One measurement after another reads more clearly than download and upload at once.
    LaunchedEffect(Unit) { viewModel.setSerial(true) }
    LaunchedEffect(connected) { if (!connected) viewModel.onVpnDisconnected() }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.cancelTest()
            viewModel.dismissMeteredWarning()
        }
    }

    val route by produceState("Direct", connected) {
        value = if (!connected) {
            "Disconnected · Test the local network directly"
        } else {
            withContext(Dispatchers.IO) { ProfileManager.get(Settings.selectedProfile)?.name }?.let { "Via $it" } ?: "Via current node"
        }
    }

    if (enabled && state.showMeteredWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMeteredWarning() },
            title = { Text("Uses mobile data") },
            text = { Text("This speed test may use several hundred MB of data. Continue?") },
            confirmButton = { TextButton(onClick = { if (enabled) viewModel.confirmMeteredStart(connected) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissMeteredWarning() }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    BackdropContrastScope(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Gauge(state, enabled) {
                if (state.isRunning) viewModel.cancelTest() else if (enabled) viewModel.requestStartTest(context, connected)
            }
            Spacer(Modifier.height(18.dp))
            RingLine(route)
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
private fun Gauge(state: NetworkQualityState, enabled: Boolean, onTap: () -> Unit) {
    val stage = stageOf(state)
    val live = when (stage) {
        Stage.Download -> state.downloadCapacity
        Stage.Upload -> state.uploadCapacity
        else -> 0L
    }
    val fill = if (live > 0) (ln(1.0 + live / 1_000_000.0) / ln(1001.0)).toFloat().coerceIn(0f, 1f) else 0f
    val running = stage == Stage.Latency || stage == Stage.Download || stage == Stage.Upload
    TestRing(
        phase = if (running) RingPhase.Running else RingPhase.Idle,
        onTap = onTap,
        enabled = enabled,
        sweep = stage == Stage.Latency,
        fill = fill,
    ) {
        AnimatedContent(targetState = stage, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "stage") { shown ->
            when (shown) {
                Stage.Download, Stage.Upload -> RingNumber(mbps(live), "Mbps")
                Stage.Latency -> RingNumber(if (state.idleLatencyMs > 0) "${state.idleLatencyMs}" else "…")
                else -> RingWord(shown.label)
            }
        }
        if (running) RingCaption(stage.label)
    }
}

/** Download, upload and latency side by side, and how responsive the line stays under load. */
@Composable
private fun Results(state: NetworkQualityState) {
    val measured = state.downloadCapacity > 0 || state.uploadCapacity > 0 || state.idleLatencyMs > 0
    TestFigures(measured) {
        TestFigure("Download", mbps(state.downloadCapacity), "Mbps")
        TestFigure("Upload", mbps(state.uploadCapacity), "Mbps")
        TestFigure("Latency", if (state.idleLatencyMs > 0) "${state.idleLatencyMs}" else "—", "ms")
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
        RingLine(verdict)
    }
}
