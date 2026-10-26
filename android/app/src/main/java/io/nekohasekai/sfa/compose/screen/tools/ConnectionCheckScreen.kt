package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.compose.theme.KnineMotion
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.connection.ConnectionCheckId
import io.nekohasekai.sfa.connection.ConnectionCheckInput
import io.nekohasekai.sfa.connection.ConnectionCheckLevel
import io.nekohasekai.sfa.connection.ConnectionCheckMode
import io.nekohasekai.sfa.connection.ConnectionCheckReport
import io.nekohasekai.sfa.connection.ConnectionChecks
import io.nekohasekai.sfa.connection.SystemVpnProtection
import io.nekohasekai.sfa.connection.VpnOwnership
import io.nekohasekai.sfa.connection.VpnProtectionSnapshot
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.udp.RunningUdpProfile
import io.nekohasekai.sfa.udp.UdpRuntime
import io.nekohasekai.sfa.utils.RemoteControlManager

/** Reads local evidence only. It never resolves a name, sends a probe, or changes the VPN. */
@Composable
internal fun ConnectionCheckScreen(
    serviceStatus: Status,
    selections: Map<String, String>,
    currentMode: String?,
    commandConnected: Boolean,
    selectedProfileId: Long? = null,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    val runtime by UdpRuntime.active.collectAsState()
    val remote by RemoteControlManager.remoteServer.collectAsState()
    var system by remember(connectivity) { mutableStateOf(readLocalVpn(connectivity)) }
    DisposableEffect(connectivity, enabled) {
        system = readLocalVpn(connectivity)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun update() { system = readLocalVpn(connectivity) }
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = update()
        }
        val registered = enabled && runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    fun evidence(running: RunningUdpProfile?, localSystem: LocalVpnSnapshot) = CheckEvidenceKey(
        runtime = running,
        local = remote == null,
        started = serviceStatus == Status.Started,
        selectedProfileId = selectedProfileId,
        mode = ConnectionCheckMode.from(currentMode),
        commandConnected = commandConnected,
        selections = selections.toMap(),
        system = localSystem,
        resumed = enabled,
    )
    val current = evidence(runtime, system)
    var checkedKey by remember { mutableStateOf<CheckEvidenceKey?>(null) }
    var report by remember { mutableStateOf<ConnectionCheckReport?>(null) }
    val visible = report?.let { if (checkedKey == current && enabled) it else ConnectionChecks.changed() }
    LaunchedEffect(current) {
        // A mode/selector round trip or resume must not resurrect an old successful snapshot.
        if (report != null && checkedKey != current) {
            report = ConnectionChecks.changed()
            checkedKey = null
        }
    }

    ConnectionCheckPanel(
        report = visible,
        enabled = enabled,
        onCheck = {
            val fresh = readLocalVpn(connectivity)
            system = fresh
            val key = evidence(UdpRuntime.active.value, fresh)
            val active = key.runtime
            report = ConnectionChecks.inspect(
                ConnectionCheckInput(
                    local = key.local,
                    started = key.started && key.resumed,
                    commandConnected = key.commandConnected,
                    mode = key.mode,
                    ownership = fresh.ownership,
                    vpnNetwork = fresh.vpnNetwork,
                    underlyingNetwork = fresh.underlyingNetwork,
                    runtimeEpoch = active?.epoch,
                    runtimeNetwork = active?.network,
                    runtimeConfig = active?.config,
                    selections = key.selections,
                    protection = fresh.protection,
                    selectedProfileMatches = selectedProfileId != null && active?.id == selectedProfileId,
                ),
            )
            checkedKey = key
        },
        onVpnSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
    )
}

/** Exact in-memory equality invalidates results on profile, mode, selector, or network change. */
private data class CheckEvidenceKey(
    val runtime: RunningUdpProfile?,
    val local: Boolean,
    val started: Boolean,
    val selectedProfileId: Long?,
    val mode: ConnectionCheckMode,
    val commandConnected: Boolean,
    val selections: Map<String, String>,
    val system: LocalVpnSnapshot,
    val resumed: Boolean,
)
private data class LocalVpnSnapshot(
    val ownership: VpnOwnership,
    val vpnNetwork: Long?,
    val underlyingNetwork: Long,
    val protection: VpnProtectionSnapshot,
)
private fun readLocalVpn(connectivity: ConnectivityManager): LocalVpnSnapshot {
    val network = runCatching { connectivity.activeNetwork }.getOrNull()
    val capabilities = network?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
    val ownership = when {
        Build.VERSION.SDK_INT < 30 || capabilities == null -> VpnOwnership.Unknown
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VpnOwnership.Other
        capabilities.ownerUid == Process.myUid() -> VpnOwnership.Ours
        capabilities.ownerUid < 0 -> VpnOwnership.Unknown
        else -> VpnOwnership.Other
    }
    return LocalVpnSnapshot(
        ownership, network?.networkHandle,
        DefaultNetworkMonitor.defaultNetwork?.networkHandle ?: 0L,
        SystemVpnProtection.snapshot(),
    )
}

/** A check's weather: in place is clear, worth a look is mist, unconfirmed is the moon. */
internal fun ConnectionCheckLevel.weather(): NodeWeather? = when (this) {
    ConnectionCheckLevel.Configured -> NodeWeather.Clear
    ConnectionCheckLevel.Attention -> NodeWeather.Mist
    ConnectionCheckLevel.Unknown -> NodeWeather.Rest
    ConnectionCheckLevel.Information -> null
}

/** The whole check in one weather and a few words, for the ring and the line under it. */
internal fun ConnectionCheckReport.verdict(): Pair<NodeWeather, String> {
    val attention = items.count { it.level == ConnectionCheckLevel.Attention }
    val unknown = items.count { it.level == ConnectionCheckLevel.Unknown }
    // One reason for every row (not connected, the network moved): say it once, not eight times.
    items.map { it.detail }.distinct().singleOrNull()?.takeIf { unknown == items.size }?.let { return NodeWeather.Rest to it }
    return when {
        attention > 0 -> NodeWeather.Mist to if (attention == 1) "1 to look at" else "$attention to look at"
        unknown > 0 -> NodeWeather.Rest to if (unknown == 1) "1 unconfirmed" else "$unknown unconfirmed"
        else -> NodeWeather.Clear to "All in place"
    }
}

@Composable
internal fun ConnectionCheckPanel(
    report: ConnectionCheckReport?,
    enabled: Boolean,
    onCheck: () -> Unit,
    onVpnSettings: () -> Unit,
) {
    val verdict = report?.verdict()
    // Rows only when they say different things; a reason shared by all is the line under the ring.
    val rows = report?.items?.takeIf { items -> items.map { it.detail }.distinct().size > 1 || items.any { it.level != ConnectionCheckLevel.Unknown } }
    BackdropContrastScope(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TestRing(
                phase = if (verdict == null) RingPhase.Idle else RingPhase.Done,
                onTap = onCheck,
                enabled = enabled,
                accent = verdict?.let { ringAccent(it.first) },
            ) {
                if (verdict == null) {
                    RingWord("Check")
                } else {
                    WeatherGlyph(verdict.first, size = 44.dp, modifier = Modifier.padding(bottom = 10.dp))
                    RingCaption("Check again")
                }
            }
            Spacer(Modifier.height(18.dp))
            RingLine(verdict?.second ?: "Not checked")
            Spacer(Modifier.height(24.dp))
            if (rows != null) {
                // Reopened rows stay open while the same report is shown, and close for a new one.
                var opened by remember(report) { mutableStateOf(emptySet<ConnectionCheckId>()) }
                rows.forEach { item ->
                    val weather = item.level.weather()
                    // What needs a look says why at once; the rest says it when asked.
                    val shown = item.level == ConnectionCheckLevel.Attention
                    TestRow(
                        title = item.id.title,
                        weather = weather,
                        value = item.count?.let { if (it == 1) "1 app" else "$it apps" },
                        detail = item.detail,
                        open = shown != (item.id in opened),
                        onToggle = { opened = if (item.id in opened) opened - item.id else opened + item.id },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            var scopeOpen by remember { mutableStateOf(false) }
            TestFold("Test scope", scopeOpen, { scopeOpen = !scopeOpen })
            AnimatedVisibility(scopeOpen, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestNote("Reads the active configuration and system switches on this phone. Nothing is sent, so it cannot rule out every leak.")
                    TestNote("Actual DNS and IPv6 egress, network changes, other apps, secondary profiles and system call routes require separate device tests.")
                    TestNote("Direct rules are explicit exceptions. Node bootstrap DNS, LAN traffic and private-network DNS must be evaluated separately.", Modifier.padding(bottom = 8.dp))
                }
            }
            TestLink("Open system VPN settings", onVpnSettings, enabled = enabled)
        }
    }
}
