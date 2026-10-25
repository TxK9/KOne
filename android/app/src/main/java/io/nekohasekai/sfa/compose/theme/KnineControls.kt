package io.nekohasekai.sfa.compose.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The readable surface colour behind the current wallpaper's ink. */
@Composable
internal fun knineSurface(): Color {
    LocalBackdrop.current?.let { return Color(it.theme.readingSurface) }
    val surface = MaterialTheme.colorScheme.surface
    if (surface.alpha > 0.5f) return surface
    // Glass sheets clear their surface colour; use the matching reading surface instead.
    val lightInk = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f
    return Color(if (lightInk) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE)
}

/**
 * A row of choices; a light knob springs under the selected one. With no valid [selected] there is
 * no knob, and an option [optionEnabled] refuses is dimmed and cannot be picked.
 */
@Composable
fun KnineSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    optionEnabled: (Int) -> Boolean = { true },
) {
    val ink = LocalContentColor.current
    val surface = knineSurface()
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val target = bounds[selected]
    val knobX by animateDpAsState(
        with(density) { (target?.first ?: 0).toDp() },
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "knobX",
    )
    val knobW by animateDpAsState(
        with(density) { (target?.second ?: 0).toDp() },
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "knobW",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ink.copy(alpha = 0.07f))
            .padding(3.dp),
    ) {
        if (target != null) {
            Box(
                Modifier
                    .offset(x = knobX)
                    .width(knobW)
                    .height(32.dp)
                    .shadow(1.dp, RoundedCornerShape(9.dp))
                    .background(surface, RoundedCornerShape(9.dp)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { index, label ->
                val enabled = optionEnabled(index)
                Box(
                    Modifier
                        .height(32.dp)
                        .widthIn(min = 40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .onGloballyPositioned { bounds[index] = it.boundsInParentX() }
                        .selectable(selected = index == selected, enabled = enabled, role = Role.RadioButton) { onSelect(index) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BackdropText(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        color = if (enabled) Color.Unspecified else ink.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

private fun LayoutCoordinates.boundsInParentX(): Pair<Int, Int> = positionInParent().x.roundToInt() to size.width

/** A small switch whose knob springs across, in the wallpaper's ink. */
@Composable
fun KnineSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ink = LocalContentColor.current
    // On, the knob is the opposite of the ink, so it shows on the full ink track whatever the page's surface is.
    val knob = Color(if (ink.luminance() > 0.5f) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE)
    val t by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "switch",
    )
    // The track is faint ink when off and full ink when on; disabled dims it further. (Setting its
    // alpha outright, as before, made the off track full ink, and the off switch a blank pill.)
    val track = lerp(ink.copy(alpha = 0.18f), ink, t.coerceIn(0f, 1f))
    Box(
        modifier
            .size(46.dp, 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track.copy(alpha = track.alpha * if (enabled) 1f else 0.4f))
            // Without its own action the switch only shows the state; the row around it takes the tap.
            .then(if (onCheckedChange != null) Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier)
            .padding(3.dp),
    ) {
        // Off, the knob is ink on the faint track; on, the opposite of the ink on the full ink. A knob in
        // the page's surface colour vanished over the dark ink paintings, both off and on.
        Box(
            Modifier
                .offset { IntOffset((18.dp.toPx() * t).roundToInt(), 0) }
                .size(22.dp)
                .shadow((2 * t.coerceIn(0f, 1f)).dp, CircleShape)
                .background(lerp(ink.copy(alpha = 0.72f), knob, t.coerceIn(0f, 1f)), CircleShape),
        )
    }
}
