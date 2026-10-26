package cc.txwd.kone.ui

import cc.txwd.kone.i18n.tr

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Something to click, the way a mouse expects: the hand over it, a faint wash of ink when the
 * pointer rests on it and a deeper one while pressed. The wash follows the thing's own clip
 * (a rounded rail item, a round button); text with no clip of its own gets a rounded wash
 * reaching [bleed] beyond it on either side. On a dark fill ([onDark]) the wash is paper.
 * Reached with Tab, it is outlined in the same ink, fully rounded so it lies inside a round
 * clip, or with [corner] when the thing is clipped to that and is not a circle or a pill.
 * Enter presses it.
 *
 * To a screen reader it is a [role] (a button unless said), named by the words inside it or by
 * [label] when it has none (an icon, ×, an arrow); a choice or a tab says whether it is
 * [selected], a switch whether it is [toggled] on.
 */
@Composable
fun Modifier.press(
    enabled: Boolean = true, onDark: Boolean = false, bleed: Dp = 0.dp, corner: Dp? = null,
    role: Role = Role.Button, label: String? = null, selected: Boolean? = null, toggled: Boolean? = null,
    onClick: () -> Unit,
): Modifier {
    val ink = LocalInk.current
    val wash = if (onDark) ink.paper.copy(alpha = 0.14f) else ink.ink.copy(alpha = if (ink.dark) 0.10f else 0.06f)
    return pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = null, indication = InkWash(wash, bleed, corner), enabled = enabled, role = role, onClick = onClick)
        .then(
            if (label == null && selected == null && toggled == null) Modifier else Modifier.semantics {
                label?.let { contentDescription = it }
                selected?.let { this.selected = it }
                toggled?.let {
                    toggleableState = ToggleableState(it)
                    stateDescription = if (it) tr("On") else tr("Off")
                }
            },
        )
}

private data class InkWash(val color: Color, val bleed: Dp, val corner: Dp?) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = WashNode(interactionSource, color, bleed, corner)
}

private class WashNode(private val source: InteractionSource, private val color: Color, private val bleed: Dp, private val corner: Dp?) : Modifier.Node(), DrawModifierNode {
    private var hovered = 0
    private var pressed = 0
    private var focused = 0
    // Focus a mouse click gave: not outlined, as the pointer already shows where it is.
    private var clicked = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovered++
                    is HoverInteraction.Exit -> hovered--
                    is PressInteraction.Press -> { pressed++; if (hovered > 0) clicked = true }
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
                    is FocusInteraction.Focus -> focused++
                    is FocusInteraction.Unfocus -> { focused--; clicked = false }
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val reach = bleed.toPx()
        val strength = when {
            pressed > 0 -> 2f
            hovered > 0 -> 1f
            else -> 0f
        }
        if (strength > 0) {
            drawRoundRect(
                color.copy(alpha = (color.alpha * strength).coerceAtMost(1f)),
                topLeft = Offset(-reach, 0f), size = Size(size.width + 2 * reach, size.height),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        }
        // The keyboard's place: a hairline of the same ink, firmer than the wash so it reads on its own.
        if (focused > 0 && !clicked) {
            val line = 1.5.dp.toPx()
            val width = size.width + 2 * reach - line
            val height = size.height - line
            val round = corner?.let { (it.toPx() - line / 2).coerceAtLeast(0f) } ?: (minOf(width, height) / 2)
            drawRoundRect(
                color.copy(alpha = (color.alpha * 8f).coerceAtMost(0.7f)),
                topLeft = Offset(-reach + line / 2, line / 2), size = Size(width, height),
                cornerRadius = CornerRadius(round), style = Stroke(line),
            )
        }
    }
}
