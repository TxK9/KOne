package cc.txwd.kone.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Something to click, the way a mouse expects: the hand over it, a faint wash of ink when the
 * pointer rests on it and a deeper one while pressed. The wash follows the thing's own clip
 * (a rounded rail item, a round button); text with no clip of its own gets a rounded wash
 * reaching [bleed] beyond it on either side. On a dark fill ([onDark]) the wash is paper.
 */
@Composable
fun Modifier.press(enabled: Boolean = true, onDark: Boolean = false, bleed: Dp = 0.dp, onClick: () -> Unit): Modifier {
    val ink = LocalInk.current
    val wash = if (onDark) ink.paper.copy(alpha = 0.14f) else ink.ink.copy(alpha = if (ink.dark) 0.10f else 0.06f)
    return pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = null, indication = InkWash(wash, bleed), enabled = enabled, onClick = onClick)
}

private data class InkWash(val color: Color, val bleed: Dp) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = WashNode(interactionSource, color, bleed)
}

private class WashNode(private val source: InteractionSource, private val color: Color, private val bleed: Dp) : Modifier.Node(), DrawModifierNode {
    private var hovered = 0
    private var pressed = 0

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovered++
                    is HoverInteraction.Exit -> hovered--
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val strength = when {
            pressed > 0 -> 2f
            hovered > 0 -> 1f
            else -> return
        }
        val reach = bleed.toPx()
        drawRoundRect(
            color.copy(alpha = (color.alpha * strength).coerceAtMost(1f)),
            topLeft = Offset(-reach, 0f), size = Size(size.width + 2 * reach, size.height),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
    }
}
