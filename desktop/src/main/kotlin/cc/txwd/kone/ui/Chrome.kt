package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Text in the page's face: Segoe UI, or what stands in for it. */
@Composable
fun Label(
    text: String,
    size: TextUnit,
    color: Color = LocalInk.current.ink,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    tabular: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    /** A line height as CSS sets one, centred on the line; unspecified keeps the font's own. */
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color, fontSize = size, fontWeight = weight, fontFamily = Fonts.ui, letterSpacing = letterSpacing,
            fontFeatureSettings = if (tabular) "tnum" else null, textAlign = TextAlign.Start,
            lineHeight = lineHeight, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
        softWrap = false,
    )
}

/** A hairline along one edge, the preview's 1px borders. */
fun Modifier.hairline(color: Color, top: Boolean = false, bottom: Boolean = false, end: Boolean = false, width: Dp = 1.dp): Modifier = drawBehind {
    val px = width.toPx()
    if (top) drawRect(color, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, px))
    if (bottom) drawRect(color, Offset(0f, size.height - px), androidx.compose.ui.geometry.Size(size.width, px))
    if (end) drawRect(color, Offset(size.width - px, 0f), androidx.compose.ui.geometry.Size(px, size.height))
}

/** The window's own title bar: the mark, the name, and the three buttons. */
@Composable
fun TitleBar(
    onMinimize: () -> Unit = {},
    onMaximize: () -> Unit = {},
    onClose: () -> Unit = {},
    drag: @Composable (content: @Composable RowScope.() -> Unit) -> Unit = { content -> Row(content = content) },
) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().height(36.dp).hairline(ink.hair, bottom = true),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            drag {
                Row(Modifier.fillMaxSize().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mark(ink.ink, 18.dp)
                    Label("KOne", 13.sp, weight = FontWeight.SemiBold, letterSpacing = 0.26.sp)
                }
            }
        }
        Cap(Glyphs.minimize, onMinimize)
        Cap(Glyphs.maximize, onMaximize)
        Cap(Glyphs.close, onClose, closes = true)
    }
}

/** A caption button: a wash of ink under the pointer, and for close, Windows' own red with a white cross. */
@Composable
private fun Cap(glyph: List<String>, onClick: () -> Unit, closes: Boolean = false) {
    val ink = LocalInk.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        closes && pressed -> Color(0xFFB3271C)
        closes && hovered -> Color(0xFFC42B1C)
        pressed -> ink.ink.copy(alpha = if (ink.dark) 0.16f else 0.10f)
        hovered -> ink.ink.copy(alpha = if (ink.dark) 0.10f else 0.06f)
        else -> Color.Transparent
    }
    Box(
        Modifier.size(46.dp, 36.dp).background(fill).clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, if (closes && (hovered || pressed)) Color.White else ink.ink, 12.dp, strokeWidth = 1f, box = 12f)
    }
}

enum class Page(val title: String, val glyph: List<String>) {
    Dashboard("Dashboard", Glyphs.dashboard),
    Nodes("Nodes", Glyphs.nodes),
    Rules("Rules", Glyphs.rules),
    Log("Logs", Glyphs.log),
    Tools("Tools", Glyphs.tools),
    Settings("Settings", Glyphs.settings),
}

/**
 * The rail down the left: Dashboard, Logs, Tools, Settings, or those of them that have something in them yet.
 * A page in [marked] has a dot by its sign (Settings, when a newer KOne is out).
 */
@Composable
fun Rail(current: Page, onPick: (Page) -> Unit, pages: List<Page> = Page.entries, marked: Set<Page> = emptySet()) {
    val ink = LocalInk.current
    Column(
        Modifier.width(84.dp).fillMaxHeight().hairline(ink.hair, end = true).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pages.forEach { page ->
            val on = page == current
            val color = if (on) ink.ink else ink.soft
            Column(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) ink.press else Color.Transparent).press { onPick(page) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    Glyph(page.glyph, color, 22.dp, strokeWidth = 1.7f)
                    if (page in marked) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(7.dp).background(NodeWeather.Cut.ink(ink.dark), CircleShape))
                }
                Spacer(Modifier.height(6.dp))
                Label(page.title, 12.sp, color)
            }
        }
    }
}
