package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

@Composable
internal fun NodeActionBubble(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    Column(Modifier.width(58.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(32.dp).background(ink.copy(alpha = .07f), CircleShape).border(.8.dp, ink.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = ink)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
