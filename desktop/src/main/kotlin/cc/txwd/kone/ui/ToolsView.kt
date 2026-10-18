package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.txwd.kone.app.Used
import cc.txwd.kone.core.Carried

/** Tools as the App fills it: the mode, the programs and sites, and what the core carries while connected. */
data class ToolsState(
    val mode: String,
    val bypass: List<String>,
    /** What the core carries now; null while not connected, and the list is left out. */
    val carried: Carried? = null,
    /** Adapter (KOne's own) or Proxy (Windows' proxy setting). */
    val way: String = "Adapter",
    /** What the core has carried today and this month; Usage shows once there is any. */
    val today: Used = Used(0, 0),
    val month: Used = Used(0, 0),
    /** Sites sent straight out in every mode, and sites through the node in Rule too. */
    val direct: List<String> = emptyList(),
    val proxied: List<String> = emptyList(),
)

class ToolsActions(
    val onMode: (String) -> Unit = {},
    val onWay: (String) -> Unit = {},
    /** A program sent straight out (true) or back through KOne (false). */
    val onBypass: (String, Boolean) -> Unit = { _, _ -> },
    /** Pick a program to send straight out. */
    val onAddProgram: () -> Unit = {},
    /** End one connection, or all of them for null. */
    val onClose: (String?) -> Unit = {},
    /** What the person typed as a site straight out (true) or through the node (false); false when it is not a site. */
    val onAddSite: (String, Boolean) -> Boolean = { _, _ -> true },
    val onRemoveSite: (String) -> Unit = {},
)

private val MODE_NAMES = listOf("Rule" to "Rule", "Global" to "Global", "Direct" to "Direct")
private val WAY_NAMES = listOf("Adapter" to "Virtual adapter", "Proxy" to "System proxy")

/** Tools: Mode, Connection method, Usage, the programs and sites, and while connected each connection, with the program behind it. */
@Composable
fun ToolsView(state: ToolsState, actions: ToolsActions) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Line("Mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    MODE_NAMES.forEach { (mode, name) -> Choice(name, mode == state.mode) { actions.onMode(mode) } }
                }
            }
            Line("Connection method") {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    WAY_NAMES.forEach { (way, name) -> Choice(name, way == state.way) { actions.onWay(way) } }
                }
            }
            if (state.month.total > 0) {
                Line("Usage") {
                    Label("Today ${size(state.today.total)} · This month ${size(state.month.total)}", 14.sp, ink.soft, tabular = true)
                }
            }
            Line("Direct apps") {
                Chips(state.bypass, { actions.onBypass(it, false) }) {
                    Box(Modifier.height(30.dp).press(bleed = 8.dp, onClick = actions.onAddProgram), contentAlignment = Alignment.Center) {
                        Label("Add", 14.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
            Line("Direct sites") {
                Chips(state.direct, actions.onRemoveSite) { AddSite { actions.onAddSite(it, true) } }
            }
            Line("Proxied sites") {
                Chips(state.proxied, actions.onRemoveSite) { AddSite { actions.onAddSite(it, false) } }
            }
        }
        state.carried?.let { carried -> Connections(carried, state.bypass, actions) }
    }
}

@Composable
private fun ColumnScope.Connections(carried: Carried, bypass: List<String>, actions: ToolsActions) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(top = 22.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Connection", 15.sp)
        Spacer(Modifier.width(14.dp))
        Label("${carried.connections.size}", 15.sp, ink.soft, tabular = true)
        Spacer(Modifier.width(22.dp))
        Label("↓ ${size(carried.down)}　↑ ${size(carried.up)}", 13.sp, ink.soft, tabular = true)
        Spacer(Modifier.weight(1f))
        if (carried.connections.isNotEmpty()) {
            Box(Modifier.height(34.dp).press(bleed = 8.dp) { actions.onClose(null) }, contentAlignment = Alignment.Center) {
                Label("Disconnect all", 14.sp, weight = FontWeight.SemiBold)
            }
        }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(carried.connections, key = { it.id }) { connection ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).hairline(ink.hair, bottom = true).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.width(170.dp)) { Wrapped(connection.program ?: "—", 13.sp, ink.ink) }
                Box(Modifier.weight(1f)) { Wrapped(connection.target, 13.sp, ink.ink) }
                Box(Modifier.width(120.dp)) { Wrapped(if (connection.via == "direct") "Direct" else connection.via, 13.sp, ink.soft) }
                // Fixed widths from here, so every row's columns stand in line.
                Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End) { Label("↓ ${size(connection.down)}", 12.sp, ink.soft, tabular = true) }
                Row(Modifier.width(90.dp), horizontalArrangement = Arrangement.End) { Label("↑ ${size(connection.up)}", 12.sp, ink.soft, tabular = true) }
                Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    val program = connection.program
                    if (program != null && program.endsWith(".exe", ignoreCase = true) && bypass.none { it.equals(program, ignoreCase = true) }) {
                        Box(Modifier.height(28.dp).press(bleed = 6.dp) { actions.onBypass(program, true) }, contentAlignment = Alignment.Center) {
                            Label("Connect directly", 12.sp, ink.soft)
                        }
                    }
                    Box(Modifier.height(28.dp).press(bleed = 6.dp) { actions.onClose(connection.id) }, contentAlignment = Alignment.Center) {
                        Label("×", 15.sp, ink.soft)
                    }
                }
            }
        }
    }
}

/** Names in pills, each taken out by its ×, and at the end [add]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(names: List<String>, onRemove: (String) -> Unit, add: @Composable () -> Unit) {
    val ink = LocalInk.current
    FlowRow(
        Modifier.padding(vertical = 12.dp).widthIn(max = 440.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEach { name ->
            Row(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(ink.press).press { onRemove(name) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label(name, 13.sp)
                Label("×", 14.sp, ink.soft)
            }
        }
        add()
    }
}

/** Add, which opens into a field for a site: Enter adds it, Esc or leaving it empty closes it. */
@Composable
private fun AddSite(onAdd: (String) -> Boolean) {
    val ink = LocalInk.current
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    if (!open) {
        Box(Modifier.height(30.dp).press(bleed = 8.dp) { open = true }, contentAlignment = Alignment.Center) {
            Label("Add", 14.sp, weight = FontWeight.SemiBold)
        }
        return
    }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    fun close() {
        open = false
        text = ""
        wrong = false
    }
    BasicTextField(
        text,
        { text = it; wrong = false },
        Modifier.width(180.dp).height(30.dp).focusRequester(focus)
            .onFocusChanged { if (focused && !it.isFocused && text.isBlank()) close(); focused = it.isFocused }
            .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { close(); true } else false }
            .hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.ink, bottom = true, width = 1.5.dp),
        singleLine = true,
        textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.ui, fontSize = 13.sp),
        cursorBrush = SolidColor(ink.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isBlank() || onAdd(text)) close() else wrong = true }),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Label("example.com", 13.sp, ink.soft)
                field()
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Line(title: String, content: @Composable RowScope.() -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).hairline(ink.hair, bottom = true), verticalAlignment = Alignment.CenterVertically) {
        Label(title, 15.sp)
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
private fun Choice(name: String, on: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.height(34.dp).press(bleed = 8.dp, onClick = onClick)
            .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(name, 14.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Text that wraps rather than being cut: names and addresses are shown whole. */
@Composable
private fun Wrapped(text: String, size: androidx.compose.ui.unit.TextUnit, color: androidx.compose.ui.graphics.Color) {
    BasicText(text, style = TextStyle(color = color, fontSize = size, fontFamily = Fonts.ui))
}

/** Bytes as people read them: 812 B, 3.4 KB, 12.6 MB, 1.2 GB. */
fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0 / 1024.0)
}
