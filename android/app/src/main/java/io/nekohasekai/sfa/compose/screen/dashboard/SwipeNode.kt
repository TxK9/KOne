package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Physical right swipe reveals explicit Share and Delete actions; while [enabled] is false the row stays shut. */
@Composable
internal fun SwipeNode(
    deletable: Boolean,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!deletable && onShare == null) { Box(modifier) { content() }; return }
    // One round action's room for each the swipe uncovers: Share, then Delete.
    val actions = (if (onShare != null) 1 else 0) + (if (deletable) 1 else 0)
    val reveal = with(LocalDensity.current) { (ACTION_SLOT * actions).dp.toPx() }
    val currentShare by rememberUpdatedState(onShare)
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settled by animateFloatAsState(offset, spring(dampingRatio = .92f, stiffness = 480f), label = "nodeActions")
    val shown = if (dragging) offset else settled
    val currentDelete by rememberUpdatedState(onDelete)
    // Selecting nodes takes the row's taps; an open row closes for it.
    LaunchedEffect(enabled) { if (!enabled) offset = 0f }
    Box(modifier.fillMaxWidth().clipToBounds().pointerInput(reveal, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val canClose = offset > 0f
            val start = offset
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, beyond ->
                if (canClose || beyond > 0f) {
                    change.consume()
                    dragging = true
                    offset = (start + beyond).coerceIn(0f, reveal)
                }
            }
            if (drag != null) {
                val completed = horizontalDrag(drag.id) { change ->
                    offset = (offset + change.positionChange().x).coerceIn(0f, reveal)
                    change.consume()
                }
                offset = if (completed && offset >= reveal * .45f) reveal else 0f
            }
            dragging = false
        }
    }) {
        if (shown > 1f) {
            // Absolute placement keeps right-swipe identical even under RTL system settings.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.align(AbsoluteAlignment.CenterLeft).width((ACTION_SLOT * actions).dp).graphicsLayer {
                    alpha = (shown / reveal).coerceIn(0f, 1f)
                    scaleX = .88f + .12f * alpha; scaleY = scaleX
                }, verticalAlignment = Alignment.CenterVertically) {
                    val ready = !dragging && offset == reveal
                    if (onShare != null) {
                        SwipeAction("Share", Icons.Outlined.IosShare, enabled = ready) { offset = 0f; currentShare?.invoke() }
                    }
                    if (deletable) {
                        SwipeAction("Delete", Icons.Outlined.DeleteOutline, enabled = ready, danger = true) { offset = 0f; currentDelete() }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().absoluteOffset { IntOffset(shown.roundToInt(), 0) }) { content() }
    }
}

/** Width each uncovered action takes, its circle centred in it. */
private const val ACTION_SLOT = 54

/**
 * A round action that fits inside the node row's own height, so uncovering it never moves the list;
 * the word is its spoken label, and Delete wears the editor's red.
 */
@Composable
private fun SwipeAction(label: String, icon: ImageVector, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val tint = if (danger) dangerInk(ink) else ink
    Box(Modifier.width(ACTION_SLOT.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = .07f))
                .border(.8.dp, tint.copy(alpha = if (danger) .45f else .18f), CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = tint)
        }
    }
}

/** The red that deleting wears, light on light ink and deep on dark. */
internal fun dangerInk(ink: Color): Color = Color(if (ink.red > 0.5f) 0xFFFF9C8F else 0xFFA3322A)
