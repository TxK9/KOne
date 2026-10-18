package cc.txwd.kone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import cc.txwd.kone.node.UserInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where a group of nodes comes from. */
enum class SourceKind { Managed, Imported, Subscription }

/**
 * One source of nodes as the Nodes page shows it: the console's, the imported ones, or a
 * subscription with what its provider says about use and expiry and how its last update went.
 */
data class NodeSection(
    val id: String,
    val kind: SourceKind,
    val title: String,
    val nodes: List<Node>,
    val info: UserInfo? = null,
    /** When the nodes were last fetched, in milliseconds; 0 never. */
    val updated: Long = 0,
    val error: String? = null,
    /** Hours between automatic updates; 0 only when asked. */
    val hours: Int = 0,
    val url: String = "",
    val updating: Boolean = false,
)

data class NodesState(
    val sections: List<NodeSection> = emptyList(),
    val selected: String? = null,
    /** Every node's delay is being tested now. */
    val testing: Boolean = false,
    /** One line on what just happened or went wrong, for a few seconds. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

class NodesActions(
    val onUse: (String) -> Unit = {},
    val onTest: () -> Unit = {},
    /** A subscription link typed or pasted; false when it is not one KOne fetches. */
    val onAddSubscription: (String) -> Boolean = { true },
    val onUpdate: (String) -> Unit = {},
    val onEdit: (id: String, name: String, url: String, hours: Int) -> Boolean = { _, _, _, _ -> true },
    val onRemoveSubscription: (String) -> Unit = {},
    val onRemoveNode: (String) -> Unit = {},
    val onImport: () -> Unit = {},
)

/** The intervals a subscription can update at, in hours; 0 only when asked. */
private val INTERVALS = listOf(0 to "Manual", 6 to "6 h", 12 to "12 h", 24 to "24 h")

/**
 * Nodes: every source of nodes, each with its nodes in aligned rows (flag, name, kind, weather
 * and delay), the chosen one checked. Subscriptions can be added, updated, edited and removed;
 * a click on a node uses it.
 */
@Composable
fun NodesView(state: NodesState, actions: NodesActions, now: Long = System.currentTimeMillis()) {
    val ink = LocalInk.current
    var adding by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            state.notice?.let { Label(it, 13.sp, if (state.noticeIsError) NodeWeather.Cut.ink(ink.dark) else ink.soft) }
            Spacer(Modifier.weight(1f))
            Action(if (state.testing) "Testing" else "Test all", enabled = !state.testing, onClick = actions.onTest)
            Action("Import", onClick = actions.onImport)
            Action("Add subscription") { adding = !adding }
        }
        if (adding) {
            Field(
                "https://",
                onDone = { url -> actions.onAddSubscription(url).also { if (it) adding = false } },
                onCancel = { adding = false },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            for (section in state.sections) {
                val open = collapsed[section.id] != true
                item(key = "head:" + section.id) {
                    Header(section, open, now, onToggle = { collapsed[section.id] = open }, actions = actions) {
                        editing = if (editing == section.id) null else section.id
                    }
                }
                if (editing == section.id && section.kind == SourceKind.Subscription) {
                    item(key = "edit:" + section.id) {
                        Editor(section, onSave = { name, url, hours -> actions.onEdit(section.id, name, url, hours).also { if (it) editing = null } }, onCancel = { editing = null })
                    }
                }
                if (open) {
                    items(section.nodes, key = { section.id + ":" + it.id }) { node ->
                        NodeRow(node, node.id == state.selected, removable = section.kind == SourceKind.Imported, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(section: NodeSection, open: Boolean, now: Long, onToggle: () -> Unit, actions: NodesActions, onEdit: () -> Unit) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 44.dp).hairline(ink.hair, bottom = true),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.height(36.dp).press(bleed = 6.dp, onClick = onToggle), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(if (open) "▾" else "▸", 13.sp, ink.soft)
            Label(section.title, 15.sp, weight = FontWeight.SemiBold)
            Label("${section.nodes.size}", 13.sp, ink.soft, tabular = true)
        }
        section.info?.let { info ->
            if (info.total > 0) Label("${size(info.used)} / ${size(info.total)}", 13.sp, ink.soft, tabular = true)
            info.expire?.let { expire -> Expiry(expire, now) }
        }
        if (section.kind == SourceKind.Subscription) {
            when {
                section.updating -> Label("Updating", 13.sp, ink.soft)
                section.error != null -> Label(section.error, 13.sp, NodeWeather.Cut.ink(ink.dark))
                section.updated > 0 -> Label("Updated ${ago(now - section.updated)}", 13.sp, ink.soft)
            }
        }
        Spacer(Modifier.weight(1f))
        if (section.kind == SourceKind.Subscription) {
            Action("Update", enabled = !section.updating) { actions.onUpdate(section.id) }
            Action("Edit", onClick = onEdit)
            Box(Modifier.height(32.dp).press(bleed = 6.dp) { actions.onRemoveSubscription(section.id) }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Label("×", 16.sp, ink.soft)
            }
        }
    }
}

@Composable
private fun Expiry(expire: Long, now: Long) {
    val ink = LocalInk.current
    val left = expire * 1000 - now
    val day = LocalDate.ofInstant(Instant.ofEpochSecond(expire), ZoneId.systemDefault())
    val color = when {
        left <= 0 -> NodeWeather.Cut.ink(ink.dark)
        left < 7 * 86_400_000L -> NodeWeather.Mist.ink(ink.dark)
        else -> ink.soft
    }
    Label(if (left <= 0) "Expired $day" else "Expires $day", 13.sp, color, tabular = true)
}

@Composable
private fun NodeRow(node: Node, on: Boolean, removable: Boolean, actions: NodesActions) {
    val ink = LocalInk.current
    val color = if (on) ink.ink else ink.soft
    Row(
        Modifier.fillMaxWidth().height(40.dp).press { actions.onUse(node.id) }.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Flag(node.country)
        Box(Modifier.weight(1f)) { Label(node.name, 14.sp, ink.ink, if (on) FontWeight.SemiBold else FontWeight.Normal) }
        // Fixed widths from here, so every row's columns stand in line.
        Box(Modifier.width(96.dp)) { Label(node.protocol.orEmpty(), 13.sp, ink.soft) }
        // How often it has not answered lately, once it has missed at all.
        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
            node.loss?.takeIf { it > 0 }?.let { loss ->
                Label("${(loss * 100).toInt()}% lost", 12.sp, if (loss >= 0.3) NodeWeather.Cut.ink(ink.dark) else ink.soft, tabular = true)
            }
        }
        Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            WeatherGlyph(node.weather, 16.dp)
            Label(delayText(node.delay), 13.sp, color, tabular = true)
        }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { if (on) Glyph(Glyphs.check, ink.ink, 16.dp, strokeWidth = 2.2f) }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (removable) {
                Box(Modifier.height(28.dp).press(bleed = 4.dp) { actions.onRemoveNode(node.id) }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Label("×", 15.sp, ink.soft)
                }
            }
        }
    }
}

/** A subscription's name, link and interval, saved together. */
@Composable
private fun Editor(section: NodeSection, onSave: (String, String, Int) -> Boolean, onCancel: () -> Unit) {
    val ink = LocalInk.current
    var name by remember(section.id) { mutableStateOf(section.title) }
    var url by remember(section.id) { mutableStateOf(section.url) }
    var hours by remember(section.id) { mutableStateOf(section.hours) }
    var wrong by remember(section.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).hairline(ink.hair, bottom = true).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditLine("Name") { Text(name, { name = it }, Modifier.width(300.dp)) }
        EditLine("Link") { Text(url, { url = it; wrong = false }, Modifier.width(420.dp), wrong = wrong) }
        EditLine("Update") {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                INTERVALS.forEach { (value, title) -> Choice(title, value == hours) { hours = value } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Spacer(Modifier.width(90.dp))
            Action("Save") { if (!onSave(name.trim(), url.trim(), hours)) wrong = true }
            Action("Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun EditLine(title: String, content: @Composable RowScope.() -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(90.dp)) { Label(title, 13.sp, ink.soft) }
        content()
    }
}

@Composable
private fun Text(value: String, onChange: (String) -> Unit, modifier: Modifier, wrong: Boolean = false) {
    val ink = LocalInk.current
    BasicTextField(
        value, onChange,
        modifier.height(32.dp).hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.hair, bottom = true, width = 1.5.dp),
        singleLine = true,
        textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.ui, fontSize = 13.sp),
        cursorBrush = SolidColor(ink.ink),
        decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { field() } },
    )
}

/** A field that takes one line: Enter gives it to [onDone] (which says whether it was taken), Esc closes it. */
@Composable
private fun Field(hint: String, onDone: (String) -> Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    BasicTextField(
        text,
        { text = it; wrong = false },
        modifier.fillMaxWidth().height(40.dp).focusRequester(focus)
            .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onCancel(); true } else false }
            .hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.ink, bottom = true, width = 1.5.dp),
        singleLine = true,
        textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.mono, fontSize = 14.sp),
        cursorBrush = SolidColor(ink.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isBlank()) onCancel() else if (!onDone(text.trim())) wrong = true }),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Label(hint, 14.sp, ink.soft)
                field()
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(Modifier.height(34.dp).press(enabled = enabled, bleed = 8.dp, onClick = { if (enabled) onClick() }).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Label(text, 14.sp, if (enabled) ink.ink else ink.soft, FontWeight.SemiBold)
    }
}

@Composable
private fun Choice(name: String, on: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.height(32.dp).press(bleed = 6.dp, onClick = onClick)
            .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(name, 13.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** How long ago, in the largest unit that fits: just now, 5 min ago, 3 h ago, 2 days ago. */
fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
