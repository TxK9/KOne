package cc.txwd.kone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What a page says when it has nothing to show yet: a faint sign of the page, a few words on
 * why, and one line on what will fill it or what to do. Quiet, centred in the space it is given,
 * and arriving with a short fade rather than flashing in.
 */
@Composable
fun EmptyState(title: String, detail: String? = null, glyph: List<String>? = null, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val still = LocalStill.current
    var shown by remember { mutableStateOf(still) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, Motion.spec(still, Motion.STANDARD + 100), label = "empty")
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp).alpha(fade),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        glyph?.let { Glyph(it, ink.soft.copy(alpha = 0.55f), 30.dp, strokeWidth = 1.5f) }
        Label(title, 15.sp, ink.ink, FontWeight.SemiBold)
        detail?.let {
            androidx.compose.foundation.text.BasicText(
                it, Modifier.widthIn(max = 420.dp),
                style = androidx.compose.ui.text.TextStyle(color = ink.soft, fontFamily = Fonts.ui, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp),
            )
        }
    }
}
