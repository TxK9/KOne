package io.nekohasekai.sfa.compose.screen.log

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
import io.nekohasekai.sfa.compose.theme.BackdropCard
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status

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
    // The state as the dashboard says it, a weather seal and a word, on the title's own left edge.
    Column(Modifier.fillMaxSize().padding(LocalScaffoldPadding.current).padding(horizontal = 16.dp, vertical = 12.dp)) {
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
            )
        }
    }
}
