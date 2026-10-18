package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/** Stateless presentation shared by diagnostics, endpoint details and reports. */
@Composable
internal fun ToolSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
internal fun ToolEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
) {
    io.nekohasekai.sfa.compose.theme.KnineEmptyState(title = title, description = message, icon = icon, modifier = modifier)
}

@Composable
internal fun ToolLoadingState(modifier: Modifier = Modifier) {
    io.nekohasekai.sfa.compose.theme.KnineLoadingState(label = stringResource(R.string.loading), modifier = modifier)
}

@Composable
internal fun ToolDetailValue(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, valueColor: Color = Color.Unspecified) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = valueColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ToolStatusSummary(label: String, status: String, color: Color) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.weight(1f))
        }
    }
}
