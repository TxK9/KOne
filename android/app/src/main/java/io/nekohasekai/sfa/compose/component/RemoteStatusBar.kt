package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.WallpaperGlass
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/** Remote controls keep a full title row and wrap actions when text is enlarged. */
@Composable
fun RemoteStatusBar(
    visible: Boolean,
    serverName: String,
    isConnected: Boolean,
    startTime: Long?,
    groupsCount: Int,
    hasGroups: Boolean,
    onGroupsClick: () -> Unit,
    connectionsCount: Int,
    onConnectionsClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        WallpaperGlass {
            Column(Modifier.fillMaxWidth().onSizeChanged { onHeightChanged(it.height) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (isConnected) serverName else stringResource(R.string.remote_connecting),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (isConnected && startTime != null) UptimeText(startTime)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (isConnected) {
                        TextButton(onClick = onConnectionsClick, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("$connectionsCount ${stringResource(R.string.title_connections)}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                        }
                        if (hasGroups) TextButton(onClick = onGroupsClick, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("$groupsCount ${stringResource(R.string.title_groups)}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                    TextButton(onClick = onDisconnectClick, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.remote_disconnect), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
