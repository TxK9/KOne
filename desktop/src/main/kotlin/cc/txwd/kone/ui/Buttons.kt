package cc.txwd.kone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * The one thing a part of the page is for (Save, Update now): a pill of ink with the words in
 * paper. Everything else stays words: bold for an action, soft for a lesser one. While [busy],
 * the pill shows what is under way instead and takes no press.
 */
@Composable
fun Primary(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: String? = null) {
    val ink = LocalInk.current
    val shape = RoundedCornerShape(17.dp)
    val live = enabled && busy == null
    Box(
        Modifier.height(34.dp).clip(shape).background(if (live) ink.ink else ink.press, shape)
            .press(enabled = live, onDark = live) { if (live) onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy != null) Busy(busy) else Label(text, 14.sp, if (live) ink.paper else ink.soft, FontWeight.SemiBold)
    }
}

/**
 * Whether a menu is open. A click on the button that opened it first closes it (the click is
 * outside the menu) and then reaches the button: [toggle] lets that click go, so it does not
 * open the menu again a moment later.
 */
@Stable
class MenuState {
    var open by mutableStateOf(false)
        private set
    private var closedAt = 0L

    fun toggle() {
        if (open || System.currentTimeMillis() - closedAt > 300) open = !open
    }

    fun dismiss() {
        if (open) closedAt = System.currentTimeMillis()
        open = false
    }
}

@Composable
fun rememberMenu(): MenuState = remember { MenuState() }

/** [InkMenu] for a [MenuState]. */
@Composable
fun InkMenu(menu: MenuState, items: List<Pair<String, () -> Unit>>, end: Boolean = true, below: Dp = 40.dp) =
    InkMenu(menu.open, menu::dismiss, items, end, below)

/**
 * A short menu of [items] [below] what opened it, at its [end] or start: a card of paper that
 * eases in from that corner and fades away, closed by a choice, by Escape or by a click elsewhere.
 */
@Composable
fun InkMenu(open: Boolean, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>, end: Boolean = true, below: Dp = 40.dp) {
    val ink = LocalInk.current
    val still = LocalStill.current
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState) return
    Popup(
        alignment = if (end) Alignment.TopEnd else Alignment.TopStart,
        // The card keeps 10 dp around it for its shadow: shifted by as much, its edge meets the button's.
        offset = with(LocalDensity.current) { IntOffset(if (end) 10.dp.roundToPx() else -10.dp.roundToPx(), below.roundToPx() - 10.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onKeyEvent = { event -> (event.key == Key.Escape && event.type == KeyEventType.KeyDown).also { if (it) onDismiss() } },
    ) {
        val corner = TransformOrigin(if (end) 1f else 0f, 0f)
        AnimatedVisibility(
            shown,
            enter = fadeIn(Motion.spec(still, Motion.QUICK + 40)) + scaleIn(Motion.spec(still, Motion.STANDARD), initialScale = 0.94f, transformOrigin = corner),
            exit = fadeOut(Motion.spec(still, Motion.QUICK)) + scaleOut(Motion.spec(still, Motion.QUICK), targetScale = 0.97f, transformOrigin = corner),
        ) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                // As wide as its longest item, so no title is cut whatever the language; room for its shadow.
                Modifier.padding(10.dp).width(IntrinsicSize.Max).widthIn(min = 176.dp)
                    .shadow(14.dp, shape, ambientColor = Color(0x2E141C19), spotColor = Color(0x2E141C19))
                    .background(ink.menu, shape).padding(6.dp),
            ) {
                items.forEach { (title, act) ->
                    Box(
                        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).press { onDismiss(); act() }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Label(title, 14.sp) }
                }
            }
        }
    }
}
