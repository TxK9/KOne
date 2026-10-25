package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.component.NodeActionBubble
import kotlin.math.roundToInt

/** Physical right swipe reveals an explicit delete action. */
@Composable
internal fun SwipeNode(deletable: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
    if (!deletable) { content(); return }
    val reveal = with(LocalDensity.current) { 66.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settled by animateFloatAsState(offset, spring(dampingRatio = .92f, stiffness = 480f), label = "nodeActions")
    val shown = if (dragging) offset else settled
    val currentDelete by rememberUpdatedState(onDelete)
    Box(Modifier.fillMaxWidth().clipToBounds().pointerInput(reveal) {
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
                Row(Modifier.align(AbsoluteAlignment.CenterLeft).width(66.dp).graphicsLayer {
                    alpha = (shown / reveal).coerceIn(0f, 1f)
                    scaleX = .88f + .12f * alpha; scaleY = scaleX
                }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NodeActionBubble("Delete", Icons.Outlined.DeleteOutline, enabled = !dragging && offset == reveal) { offset = 0f; currentDelete() }
                }
            }
        }
        Box(Modifier.fillMaxWidth().absoluteOffset { IntOffset(shown.roundToInt(), 0) }) { content() }
    }
}
