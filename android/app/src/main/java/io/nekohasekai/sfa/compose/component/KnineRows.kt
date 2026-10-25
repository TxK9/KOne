package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineBadge as Badge

/*
 * The hubs' rows (Settings, Tools): one card per group, the icon in the accent, the title, a line
 * under it when there is something to say, and a chevron, or a dot where something waits. The
 * card clips a press to its own corners, so a row never rounds itself.
 */

/** A group of rows on one card, inset from the screen edge as the hubs lay them out. */
@Composable
internal fun KnineRowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(content = content)
    }
}

/**
 * A row that leads somewhere. With a [badge], a dot in that colour replaces the chevron and
 * [badgeDescription] tells a screen reader what it means.
 */
@Composable
internal fun KnineNavRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    badge: Color? = null,
    badgeDescription: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        // The dot sits where the chevron would, so a column of rows keeps one trailing edge.
        trailingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (badge != null) Badge(containerColor = badge) else KnineChevron()
            }
        },
        modifier = modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (badge != null && badgeDescription != null) Modifier.semantics { stateDescription = badgeDescription } else Modifier),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The quiet arrow at the end of a row that opens a page. */
@Composable
internal fun KnineChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
