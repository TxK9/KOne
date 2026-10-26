package cc.txwd.kone.ui

import cc.txwd.kone.i18n.tr


import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The license texts KOne carries (resources/licenses): the overview of what KOne includes and
 * under which license, then each full text. The same files lie beside KOne.exe once installed
 * (app\resources\licenses), so they can be read without opening the App.
 */
object Licenses {
    /** Each text by the name shown for it, the overview first. */
    val texts = listOf(
        tr("Overview") to "NOTICES.txt",
        "GPL-3.0" to "GPL-3.0.txt",
        "Apache-2.0" to "Apache-2.0.txt",
        "Wintun" to "Wintun.txt",
        "BSD (Go)" to "BSD-3-Clause-Go.txt",
        "BSD (Skia)" to "BSD-3-Clause-Skia.txt",
        "MIT (v2fly)" to "MIT-v2fly.txt",
        "SIL OFL" to "OFL-1.1.txt",
        "SunCalc" to "suncalc.txt",
        tr("Rule sets") to "rule-sets.txt",
    )

    /** The text of [file], or an empty one should it be missing. */
    fun text(file: String): String =
        Licenses::class.java.getResourceAsStream("/licenses/$file")?.use { it.readBytes().decodeToString() }.orEmpty()
}

/**
 * The third-party notices, opened in Settings: a choice of text, and the text itself in a frame
 * that scrolls on its own. It fades in and brings itself into view, as it opens below the last
 * line of the page. Reached with Tab, the frame scrolls with the arrows, Page Up and Down, Home and End.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LicensesPanel() {
    val ink = LocalInk.current
    val still = LocalStill.current
    var shown by remember { mutableStateOf(Licenses.texts.first()) }
    val text = remember(shown) { Licenses.text(shown.second) }
    val view = remember { BringIntoViewRequester() }
    var opened by remember { mutableStateOf(still) }
    LaunchedEffect(Unit) { opened = true }
    // Brought into view once placed, when the page knows where it is.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(placed) { if (placed) view.bringIntoView() }
    val fade by animateFloatAsState(if (opened) 1f else 0f, Motion.spec(still, Motion.STANDARD), label = "licenses")
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(view).onPlaced { placed = true }.alpha(fade).padding(top = 10.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Licenses.texts.forEach { choice ->
                val on = choice == shown
                Box(
                    Modifier.height(30.dp).press(bleed = 6.dp, role = Role.Tab, selected = on) { shown = choice }
                        .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) { Label(choice.first, 13.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        // Each text starts at its top.
        val scroll = remember(shown) { ScrollState(0) }
        TextFrame(text, shown.first, scroll)
    }
}

/** [text] in a hairline frame of its own height up to 360 dp, scrolled by [scroll] and by the keyboard once reached. */
@Composable
private fun TextFrame(text: String, name: String, scroll: ScrollState) {
    val ink = LocalInk.current
    val still = LocalStill.current
    val scope = rememberCoroutineScope()
    val frame = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(0) }
    // Three lines of text a press of an arrow, as far as the display scales them.
    val line = with(LocalDensity.current) { 54.sp.roundToPx() }
    // Where the keys have sent the text while it is on its way there, so a held key adds up
    // rather than each press starting over from wherever the last one had got to.
    var target by remember(scroll) { mutableStateOf<Int?>(null) }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 360.dp)
            .onSizeChanged { height = it.height }
            // The keyboard's place, as every control shows it: the frame's hairline in firmer ink.
            .border(if (focused) 1.5.dp else 1.dp, if (focused) ink.ink.copy(alpha = if (ink.dark) 0.7f else 0.48f) else ink.hair, frame)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val page = (height * 0.85f).toInt().coerceAtLeast(line)
                val from = target ?: scroll.value
                val to = when (event.key) {
                    Key.DirectionDown -> from + line
                    Key.DirectionUp -> from - line
                    Key.PageDown, Key.Spacebar -> from + page
                    Key.PageUp -> from - page
                    Key.MoveHome -> 0
                    Key.MoveEnd -> scroll.maxValue
                    else -> return@onKeyEvent false
                }.coerceIn(0, scroll.maxValue)
                target = to
                scope.launch {
                    try {
                        if (still) scroll.scrollTo(to) else scroll.animateScrollTo(to, tween(Motion.STANDARD, easing = Motion.Settle))
                    } finally {
                        if (target == to) target = null
                    }
                }
                true
            }
            .semantics { contentDescription = tr("{0} text" , name) }
            .focusable()
            .fadingEdges(scroll, ink.paper),
    ) {
        BasicText(
            text,
            Modifier.verticalScroll(scroll).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 16.dp + ROOM),
            style = TextStyle(color = ink.soft, fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 18.sp),
        )
        InkScrollbar(scroll, Modifier.align(Alignment.TopEnd).padding(vertical = 6.dp), gutter = (-6).dp)
    }
}
