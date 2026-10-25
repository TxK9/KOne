package cc.txwd.kone.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * KOne's scrollbar: a thin stroke of ink at the right edge of what scrolls, faint until the
 * pointer comes to it, and only there when there is more than shows. It must stay inside the
 * bounds of what holds it (a click outside them never reaches it), so a list leaves it room
 * with [ROOM] of padding on its right rather than the bar reaching into the page's margin.
 */
@Composable
fun InkScrollbar(state: LazyListState, modifier: Modifier = Modifier, gutter: Dp = 0.dp) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.fillMaxHeight().offset(x = gutter), style = inkScrollbar())
}

@Composable
fun InkScrollbar(state: ScrollState, modifier: Modifier = Modifier, gutter: Dp = 0.dp) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.fillMaxHeight().offset(x = gutter), style = inkScrollbar())
}

/**
 * Paper fading over the edge where a scrolling area cuts its content off, top or bottom, only
 * while there is more that way: a row half shown reads as more to come rather than as broken.
 */
fun Modifier.fadingEdges(state: ScrollableState, paper: Color, reach: Dp = 26.dp): Modifier = drawWithContent {
    drawContent()
    val h = reach.toPx().coerceAtMost(size.height / 3)
    if (state.canScrollBackward) {
        drawRect(Brush.verticalGradient(listOf(paper, paper.copy(alpha = 0f)), startY = 0f, endY = h), size = Size(size.width, h))
    }
    if (state.canScrollForward) {
        drawRect(
            Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper), startY = size.height - h, endY = size.height),
            topLeft = Offset(0f, size.height - h), size = Size(size.width, h),
        )
    }
}

/** The room a list leaves on its right for the scrollbar. */
val ROOM = 16.dp

@Composable
private fun inkScrollbar(): ScrollbarStyle {
    val ink = LocalInk.current
    return ScrollbarStyle(
        minimalHeight = 36.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = Motion.STANDARD,
        unhoverColor = ink.ink.copy(alpha = if (ink.dark) 0.20f else 0.16f),
        hoverColor = ink.ink.copy(alpha = if (ink.dark) 0.45f else 0.38f),
    )
}
