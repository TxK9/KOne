package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R

@Composable
fun TaildropTransferItem(
    name: String,
    peerLabel: String,
    transferredBytes: Long,
    totalBytes: Long,
    onCancel: (() -> Unit)?,
    finished: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    transferSubtitle(peerLabel, transferredBytes, totalBytes, finished),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(
                            if (finished) R.string.taildrop_dismiss else android.R.string.cancel,
                        ),
                        tint = if (finished) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
        if (!finished) {
            if (totalBytes > 0) {
                Text(
                    text = "${(transferredBytes.toDouble() / totalBytes * 100).coerceIn(0.0, 100.0).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.align(Alignment.End),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (totalBytes < 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = {
                        if (totalBytes == 0L) 1f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            }
        }
    }
}

private fun transferSubtitle(
    peerLabel: String,
    transferredBytes: Long,
    totalBytes: Long,
    finished: Boolean,
): String {
    val progress = when {
        totalBytes < 0 -> Libbox.formatBytes(transferredBytes)
        finished && transferredBytes >= totalBytes -> Libbox.formatBytes(totalBytes)
        else -> "${Libbox.formatBytes(transferredBytes)} / ${Libbox.formatBytes(totalBytes)}"
    }
    if (peerLabel.isEmpty()) {
        return progress
    }
    return "$progress · $peerLabel"
}
