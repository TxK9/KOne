package cc.txwd.kone.ui

import cc.txwd.kone.i18n.tr


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which lines Logs shows: all, warnings and errors, or errors only. */
enum class LogLevel(private val englishTitle: String) { All("All"), Warnings("Warnings"), Errors("Errors");
    val title: String get() = tr(englishTitle)
}

/** How serious a line of the core's is, by the level it names: 2 an error, 1 a warning, 0 anything else. */
fun severity(line: String): Int = when {
    Regex("""\b(ERROR|FATAL|PANIC)\b""").containsMatchIn(line) -> 2
    Regex("""\bWARN\b""").containsMatchIn(line) -> 1
    else -> 0
}

/**
 * Logs: the core's last lines, newest at the foot, in Consolas so the columns line up. A search
 * and a level narrow them; Pause holds them still to read; Copy puts what is shown on the
 * clipboard (for sending to whoever helps), Clear starts afresh.
 */
@Composable
fun LogView(lines: List<String>, onCopy: (List<String>) -> Unit = {}, onClear: () -> Unit = {}) {
    val ink = LocalInk.current
    var level by remember { mutableStateOf(LogLevel.All) }
    var query by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<List<String>?>(null) }
    val source = held ?: lines
    val shown = source.filter { line ->
        severity(line) >= level.ordinal && (query.isBlank() || line.contains(query.trim(), ignoreCase = true))
    }
    // Opened at the newest line; afterwards it follows new lines only when it is not already there,
    // so nothing scrolls (and no layout runs off the frame) when there is nothing to follow.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (shown.size - 1).coerceAtLeast(0))
    LaunchedEffect(shown.size, held) {
        val newest = shown.size - 1
        val showing = list.firstVisibleItemIndex == newest || list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == newest
        if (newest >= 0 && held == null && !showing) list.scrollToItem(newest)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
        if (lines.isNotEmpty() || held != null) {
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    query, { query = it },
                    Modifier.width(220.dp).height(32.dp).hairline(ink.hair, bottom = true, width = 1.5.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.ui, fontSize = 13.sp),
                    cursorBrush = SolidColor(ink.ink),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Label(tr("Search"), 13.sp, ink.soft)
                            field()
                        }
                    },
                )
                LogLevel.entries.forEach { choice -> Choice(choice.title, choice == level) { level = choice } }
                Box(Modifier.weight(1f))
                Action(if (held == null) "Pause" else "Resume", ink.soft) { held = if (held == null) lines.toList() else null }
                Action(tr("Copy")) { onCopy(shown) }
                Action(tr("Clear"), ink.soft) {
                    held = null
                    onClear()
                }
            }
        }
        when {
            lines.isEmpty() && held == null -> EmptyState(
                tr("Nothing logged yet"), "What KOne and the connection do appears here as it happens: warnings in amber, errors in red.", Glyphs.log,
            )
            shown.isEmpty() -> EmptyState(tr("No lines match"), if (level == LogLevel.All) "Try fewer words." else "Try fewer words, or All.")
            else -> Box(Modifier.fillMaxSize().fadingEdges(list, ink.paper)) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(end = ROOM)) {
                    items(shown) { line ->
                        val color = when (severity(line)) {
                            2 -> NodeWeather.Cut.ink(ink.dark)
                            1 -> NodeWeather.Mist.ink(ink.dark)
                            else -> ink.soft
                        }
                        BasicText(line, Modifier.padding(vertical = 2.dp), style = TextStyle(color = color, fontFamily = Fonts.mono, fontSize = 13.sp))
                    }
                }
                InkScrollbar(list, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun Action(text: String, color: androidx.compose.ui.graphics.Color = LocalInk.current.ink, onClick: () -> Unit) {
    Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onClick), contentAlignment = Alignment.Center) {
        Label(text, 14.sp, color, if (color == LocalInk.current.ink) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Choice(name: String, on: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.height(32.dp).press(bleed = 6.dp, role = Role.Tab, selected = on, onClick = onClick)
            .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(name, 13.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}
