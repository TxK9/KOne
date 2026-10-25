package io.nekohasekai.sfa.compose.screen.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.screen.tools.TestGutter
import io.nekohasekai.sfa.devices.Availability
import io.nekohasekai.sfa.devices.DeviceResult
import io.nekohasekai.sfa.devices.EngineKind
import io.nekohasekai.sfa.devices.EngineStatus
import io.nekohasekai.sfa.devices.LinkState
import io.nekohasekai.sfa.devices.ProxyMode
import io.nekohasekai.sfa.devices.RunState
import java.util.Locale
import kotlin.math.roundToLong
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/*
 * The words device management speaks, and the page it is laid on. A surface nothing drives yet
 * says "Not connected"; one the connected integration lacks says "Not available". Neither ever
 * shows a value it was not given.
 */

/** Content narrower than a tablet stays a readable column in the middle. */
private val PageWidth = 640.dp

internal const val DimAlpha = 0.45f

/** Samples the waterline keeps: about a minute at one sample a second. */
internal const val TRAFFIC_SLOTS = 60

@Composable
internal fun DevicePage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = PageWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            content = content,
        )
    }
}

/** A failure in the integration's own words, with the storm glyph. */
@Composable
internal fun ProblemLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = TestGutter * 2, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeatherGlyph(NodeWeather.Cut, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Words ------------------------------------------------------------------------------------

internal const val NotConnectedWord = "Not connected"
internal const val NotAvailableWord = "Not available"
internal const val Dash = "—"

internal val EngineKind.title: String
    get() = when (this) {
        EngineKind.SingBox -> "sing-box"
        EngineKind.Mihomo -> "Mihomo"
        EngineKind.Xray -> "Xray"
        EngineKind.Custom -> "Custom engine"
    }

internal val EngineKind.family: String
    get() = when (this) {
        EngineKind.SingBox -> "Universal proxy platform"
        EngineKind.Mihomo -> "Clash Meta"
        EngineKind.Xray -> "Project X"
        EngineKind.Custom -> "Your own integration"
    }

internal val EngineKind.icon: ImageVector
    get() = when (this) {
        EngineKind.SingBox -> Icons.Outlined.Inventory2
        EngineKind.Mihomo -> Icons.AutoMirrored.Outlined.AltRoute
        EngineKind.Xray -> Icons.Outlined.Bolt
        EngineKind.Custom -> Icons.Outlined.Extension
    }

internal val ProxyMode.label: String
    get() = when (this) {
        ProxyMode.Global -> "Global"
        ProxyMode.Rule -> "Rule"
        ProxyMode.Direct -> "Direct"
    }

/** A surface that has no value to show: why, in a word. Null when it is ready. */
internal fun Availability.word(): String? = when (this) {
    Availability.NotConnected -> NotConnectedWord
    Availability.Unsupported -> NotAvailableWord
    Availability.Ready -> null
}

internal fun engineState(availability: Availability, status: EngineStatus?): Pair<NodeWeather, String> = when {
    availability != Availability.Ready -> NodeWeather.Rest to availability.word().orEmpty()
    status == null -> NodeWeather.Mist to "Waiting"
    else -> runState(status.state)
}

internal fun runState(state: RunState): Pair<NodeWeather, String> = when (state) {
    RunState.Running -> NodeWeather.Clear to "Running"
    RunState.Starting -> NodeWeather.Mist to "Starting"
    RunState.Stopping -> NodeWeather.Mist to "Stopping"
    RunState.Stopped -> NodeWeather.Rest to "Stopped"
    RunState.Failed -> NodeWeather.Cut to "Failed"
}

internal fun linkState(integration: Availability, link: LinkState): Pair<NodeWeather, String> = when {
    integration != Availability.Ready -> NodeWeather.Rest to integration.word().orEmpty()
    else -> when (link) {
        LinkState.NotConnected -> NodeWeather.Rest to NotConnectedWord
        LinkState.Connecting -> NodeWeather.Mist to "Connecting"
        LinkState.Connected -> NodeWeather.Clear to "Connected"
        is LinkState.Lost -> NodeWeather.Cut to "Connection lost"
    }
}

internal fun problemText(problem: DeviceResult<Nothing>): String = when (problem) {
    DeviceResult.NotConnected -> NotConnectedWord
    DeviceResult.Unsupported -> NotAvailableWord
    is DeviceResult.Failure -> problem.message
    // A problem is never a success; the type cannot say so.
    is DeviceResult.Success -> ""
}

/** A measured rate in the app's units ("840 B/s", "12.4 KB/s", "3.1 MB/s"); a dash when unmeasured. */
internal fun rate(bytesPerSecond: Long?): String {
    if (bytesPerSecond == null) return Dash
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytesPerSecond.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (unit == 0 || value >= 100) value.roundToLong().toString() else String.format(Locale.US, "%.1f", value)
    return "$number ${units[unit]}/s"
}
