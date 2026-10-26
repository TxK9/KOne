package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.compose.theme.KnineMotion
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.udp.*
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/** A small, local-only check of the connected profile. It never switches a node or tests direct. */
@Composable
internal fun UdpTestScreen(
    profileId: Long?,
    selections: Map<String, String>,
    serviceStatus: Status,
    checker: UdpCheckController,
    enabled: Boolean = true,
) {
    val state by checker.state.collectAsState()
    val runtime by UdpRuntime.active.collectAsState()
    val remote by RemoteControlManager.remoteServer.collectAsState()
    val context = LocalContext.current
    val targetProfileId = profileId ?: runtime?.id
        ?: io.nekohasekai.sfa.database.Settings.selectedProfile.takeIf { it > 0 }
    val savedName by produceState("current node", targetProfileId) {
        value = targetProfileId?.let { id ->
            withContext(Dispatchers.IO) { ProfileManager.get(id)?.name }
        } ?: "current node"
    }
    val running = runtime
    val target = remember(running, selections, serviceStatus, remote, profileId, enabled) {
        udpProfileForTest(profileId, running, serviceStatus == Status.Started && enabled, remote != null)
            ?.let { UdpTargets.resolve(it, selections) }
    }
    LaunchedEffect(target) { checker.bind(target) }
    DisposableEffect(checker) { onDispose { checker.cancel() } }
    val unavailable = when {
        remote != null -> "Return to the local connection before testing"
        serviceStatus != Status.Started -> "Connect to a node first"
        profileId != null && running?.id != profileId -> "Switch to this node before testing."
        !enabled -> "Return to this page to test"
        else -> "Loading routes. Select a proxy node if the current route is direct."
    }
    val visibleState = if (state.target == target) state else UdpCheckState()
    BackdropContrastScope(Modifier.fillMaxSize()) {
        UdpTestPanel(savedName, visibleState, unavailable,
            onStart = {
                val current = udpProfileForTest(
                    profileId, UdpRuntime.active.value,
                    serviceStatus == Status.Started && enabled,
                    RemoteControlManager.remoteServer.value != null,
                )?.let { UdpTargets.resolve(it, selections) }
                if (current != null && current == target && checker.state.value.target == current) checker.start()
            }, onCancel = checker::cancel,
            onWifiSettings = {
                val intent = Intent("android.settings.WIFI_CALLING_SETTINGS")
                try { context.startActivity(intent) }
                catch (_: Exception) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
            }, connectionSettings = {
                if (targetProfileId != null) {
                    CallingOptionsSection(
                        targetProfileId,
                        editable = running?.id == targetProfileId && serviceStatus == Status.Started && remote == null && enabled,
                    )
                } else {
                    TestNote("Connect to a node to set up call compatibility.", Modifier.padding(vertical = 8.dp))
                }
            })
    }
}

/**
 * The UDP test in the speed test's shape: the ring to tap, the node it goes through, then the
 * verdict and the reply time side by side. Wi-Fi Calling folds away underneath.
 */
@Composable
internal fun UdpTestPanel(name: String, state: UdpCheckState, unavailable: String, onStart: () -> Unit, onCancel: () -> Unit, onWifiSettings: () -> Unit, connectionSettings: @Composable () -> Unit = {}) {
    val target = state.target
    val running = state.phase == UdpPhase.Running
    val testable = target != null && !target.tcpOnly
    val passed = state.phase == UdpPhase.Passed
    val failed = state.phase == UdpPhase.Failed
    val latency = state.measurement?.reply?.latencyMs?.takeIf { passed }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TestRing(
            phase = when {
                running -> RingPhase.Running
                passed || failed -> RingPhase.Done
                else -> RingPhase.Idle
            },
            onTap = if (running) onCancel else onStart,
            enabled = running || testable,
            accent = when {
                passed -> ringAccent(NodeWeather.Clear)
                failed -> ringAccent(NodeWeather.Cut)
                else -> null
            },
        ) {
            when {
                running -> {
                    RingNumber("…")
                    RingCaption("Testing")
                }
                target != null && target.tcpOnly -> RingWord("TCP only")
                passed || failed -> RingWord("Test again")
                else -> RingWord("Start")
            }
        }
        Spacer(Modifier.height(18.dp))
        RingLine(if (target == null) unavailable else "Via $name")
        Spacer(Modifier.height(36.dp))
        TestFigures(measured = passed || failed) {
            TestFigure(
                "UDP",
                weather = when {
                    passed -> NodeWeather.Clear
                    failed -> NodeWeather.Cut
                    else -> null
                },
                word = when {
                    passed -> "Available"
                    failed -> "No reply"
                    target != null && target.tcpOnly -> "Off"
                    else -> "—"
                },
            )
            TestFigure("Reply", latency?.toString() ?: "—", "ms")
        }
        Spacer(Modifier.height(28.dp))
        var wifiOpen by remember { mutableStateOf(false) }
        TestFold("Wi-Fi Calling", wifiOpen, { wifiOpen = !wifiOpen })
        AnimatedVisibility(wifiOpen, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            Column(Modifier.fillMaxWidth()) {
                connectionSettings()
                TestLink("Open system settings", onWifiSettings)
                TestNote("A UDP reply does not confirm Wi-Fi Calling: the carrier, the SIM and the system's call route decide that.", Modifier.padding(top = 4.dp))
            }
        }
    }
}
