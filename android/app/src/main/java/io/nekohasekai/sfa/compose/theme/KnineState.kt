package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Empty, unavailable and filtered states share one quiet visual hierarchy. */
@Composable
fun KnineEmptyState(
    title: String,
    description: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        WallpaperGlass(Modifier.widthIn(max = 360.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(64.dp).then(if (LocalTransparentUi.current) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    BackdropIcon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                }
                BackdropText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                description?.takeIf { it.isNotBlank() }?.let {
                    BackdropText(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                if (action != null) {
                    Spacer(Modifier.height(4.dp))
                    action()
                }
            }
        }
    }
}

@Composable
fun KnineLoadingState(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            BackdropText(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}
