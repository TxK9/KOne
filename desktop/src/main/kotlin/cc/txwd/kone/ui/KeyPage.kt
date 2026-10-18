package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** What the key page shows: the key as typed, whether a claim is under way, and why the last one failed. */
data class KeyState(val key: String = "", val busy: Boolean = false, val error: String? = null) {
    /** A claim code is longer than this; shorter input cannot be one, so Get started waits. */
    val ready: Boolean get() = key.trim().length >= 8 && !busy
}

/**
 * Activation: the painting behind, the paper wiped clear on the left, and on it KOne, the line
 * that asks for the key, the key field with its paste button, and Get started.
 */
@Composable
fun KeyView(state: KeyState, onKey: (String) -> Unit, onPaste: () -> Unit, onStart: () -> Unit, moving: Boolean = true) {
    val ink = LocalInk.current
    Box(Modifier.fillMaxSize()) {
        Handscroll(moving = moving)
        Box(
            Modifier.width(620.dp).fillMaxHeight().background(
                Brush.horizontalGradient(0f to ink.veil.copy(alpha = 0.96f), 0.62f to ink.veil.copy(alpha = 0.86f), 1f to ink.veil.copy(alpha = 0f)),
            ),
        )
        Column(
            Modifier.offset(x = 72.dp).width(380.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
        ) {
            Column {
                Label("KOne", 48.sp, weight = FontWeight.Bold, letterSpacing = 0.48.sp, lineHeight = 1.05.em)
                Spacer(Modifier.height(10.dp))
                Label("Enter an invitation code to begin", 16.sp, ink.soft)
            }
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Column {
                    Label("Invitation code", 13.sp, ink.soft)
                    Row(
                        Modifier.padding(top = 6.dp).fillMaxWidth().hairline(ink.ink, bottom = true, width = 1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicTextField(
                            state.key,
                            onKey,
                            Modifier.weight(1f).height(46.dp),
                            enabled = !state.busy,
                            singleLine = true,
                            textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.mono, fontSize = 17.sp, letterSpacing = 0.04.em),
                            cursorBrush = SolidColor(ink.ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (state.ready) onStart() }),
                            decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { field() } },
                        )
                        Box(Modifier.size(44.dp).clip(CircleShape).press(enabled = !state.busy, onClick = onPaste), contentAlignment = Alignment.Center) {
                            Glyph(Glyphs.paste, ink.ink, 20.dp, strokeWidth = 1.7f)
                        }
                    }
                    state.error?.let {
                        Label(it, 13.sp, NodeWeather.Cut.ink(ink.dark), modifier = Modifier.padding(top = 8.dp))
                    }
                }
                val shape = RoundedCornerShape(25.dp)
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(shape).background(if (state.ready) ink.ink else ink.press, shape)
                        .press(enabled = state.ready, onDark = state.ready, onClick = onStart),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(
                        if (state.busy) "Activating" else "Get started", 16.sp,
                        if (state.ready) ink.paper else ink.soft, FontWeight.SemiBold, letterSpacing = 1.28.sp,
                    )
                }
            }
        }
    }
}
