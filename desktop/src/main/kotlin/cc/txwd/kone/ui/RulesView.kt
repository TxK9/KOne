package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import cc.txwd.kone.shared.ListStatus
import cc.txwd.kone.shared.Categories
import cc.txwd.kone.core.UserRule

/** Rules as the App fills it: the programs and sites lists, the person's own rules in order, and ad blocking. */
data class RulesState(
    val bypass: List<String> = emptyList(),
    /** Sites sent straight out in every mode, and sites through the node in Rule too. */
    val direct: List<String> = emptyList(),
    val proxied: List<String> = emptyList(),
    val rules: List<UserRule> = emptyList(),
    val blockAds: Boolean = false,
    /** How each category list the rules name stands, and whether KOne is looking at them now. */
    val lists: Map<String, ListStatus> = emptyMap(),
    val listsBusy: Boolean = false,
    /** Rule mode sends what nothing matched straight out rather than through the node. */
    val unmatchedDirect: Boolean = false,
    /** One line on what just happened (an import), for a few seconds. */
    val notice: String? = null,
    /** The time now, for "updated 3 h ago". */
    val now: Long = System.currentTimeMillis(),
)

class RulesActions(
    /** A program sent straight out (true) or back through KOne (false). */
    val onBypass: (String, Boolean) -> Unit = { _, _ -> },
    /** Pick a program to send straight out. */
    val onAddProgram: () -> Unit = {},
    /** What the person typed as a site straight out (true) or through the node (false); false when it is not a site. */
    val onAddSite: (String, Boolean) -> Boolean = { _, _ -> true },
    val onRemoveSite: (String) -> Unit = {},
    val onBlockAds: (Boolean) -> Unit = {},
    /** A new rule at the end; false when its value does not fit its kind. */
    val onAddRule: (UserRule) -> Boolean = { true },
    val onRemoveRule: (Int) -> Unit = {},
    /** Moves the rule at an index one place up (true) or down. */
    val onMoveRule: (Int, Boolean) -> Unit = { _, _ -> },
    /** Downloads every category list again if it changed. */
    val onUpdateLists: () -> Unit = {},
    /** Rules pasted from a Clash profile or v2rayN (the clipboard). */
    val onImportRules: () -> Unit = {},
    /** What nothing matched: straight out (true) or through the node. */
    val onUnmatched: (Boolean) -> Unit = {},
)

/** The kinds and actions as the page names them. */
private val KINDS = listOf(
    "set" to "Category", "suffix" to "Suffix", "domain" to "Domain", "keyword" to "Keyword", "cidr" to "IP range", "program" to "App", "port" to "Port",
)
private val ACTIONS = listOf("proxy" to "Proxy", "direct" to "Direct", "block" to "Block")
private val HINTS = mapOf(
    "set" to "Pick one below, or geosite-… / geoip-…", "suffix" to "example.com", "domain" to "www.example.com", "keyword" to "example", "cidr" to "203.0.113.0/24", "program" to "Example.exe", "port" to "443 or 8000-9000",
)

/**
 * Rules: ad blocking, the apps and sites sent one way or the other, and the person's own rules,
 * tried in order after them; the first that matches wins. The Claude block comes before all of it.
 */
@Composable
fun RulesView(state: RulesState, actions: RulesActions, openWith: String? = null) {
    val ink = LocalInk.current
    // [openWith] opens the new rule's form on that kind (the screenshots use it).
    var adding by remember { mutableStateOf(openWith != null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            // What everything below leaves over: the node by default, or straight out, so only what is chosen goes through.
            Line("When nothing matches") {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Choice("Through the node", !state.unmatchedDirect) { actions.onUnmatched(false) }
                    Choice("Straight out", state.unmatchedDirect) { actions.onUnmatched(true) }
                }
            }
            Line("Block ads") { Switch(state.blockAds) { actions.onBlockAds(!state.blockAds) } }
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
        Row(Modifier.fillMaxWidth().padding(top = 22.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Rules", 15.sp)
            Spacer(Modifier.width(14.dp))
            Label("${state.rules.size}", 15.sp, ink.soft, tabular = true)
            Spacer(Modifier.width(22.dp))
            if (state.rules.isNotEmpty()) Label("First match wins", 13.sp, ink.soft)
            Spacer(Modifier.weight(1f))
            if (state.lists.isNotEmpty()) {
                val oldest = state.lists.values.mapNotNull { it.checked }.minOrNull()
                Label(
                    when {
                        state.listsBusy -> "Updating category lists"
                        oldest == null -> "Category lists not downloaded yet"
                        else -> "Category lists checked ${ago(state.now - oldest)}"
                    },
                    13.sp, ink.soft,
                )
                Spacer(Modifier.width(18.dp))
                Box(Modifier.height(34.dp).press(enabled = !state.listsBusy, bleed = 8.dp) { if (!state.listsBusy) actions.onUpdateLists() }, contentAlignment = Alignment.Center) {
                    Label("Update", 14.sp, if (state.listsBusy) ink.soft else ink.ink, FontWeight.SemiBold)
                }
                Spacer(Modifier.width(22.dp))
            }
            Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = actions.onImportRules), contentAlignment = Alignment.Center) {
                Label("Import", 14.sp, ink.soft)
            }
            Spacer(Modifier.width(22.dp))
            Box(Modifier.height(34.dp).press(bleed = 8.dp) { adding = !adding }, contentAlignment = Alignment.Center) {
                Label("Add rule", 14.sp, weight = FontWeight.SemiBold)
            }
        }
        state.notice?.let { Label(it, 13.sp, ink.soft, modifier = Modifier.padding(bottom = 6.dp)) }
        if (adding) NewRule(openWith ?: "suffix", onAdd = { rule -> actions.onAddRule(rule).also { if (it) adding = false } }, onCancel = { adding = false })
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(state.rules) { index, rule ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).hairline(ink.hair, bottom = true),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.width(90.dp)) { Label(KINDS.firstOrNull { it.first == rule.kind }?.second ?: rule.kind, 13.sp, ink.soft) }
                    if (rule.kind == "set") {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Label(Categories.title(rule.value) ?: rule.value, 13.sp)
                            if (Categories.title(rule.value) != null) Label(rule.value, 12.sp, ink.soft)
                            val status = state.lists[rule.value]
                            when {
                                status == null || (!status.kept && status.error == null) ->
                                    Label(if (state.listsBusy) "Downloading" else "Not downloaded yet", 12.sp, NodeWeather.Mist.ink(ink.dark))
                                !status.kept -> Label("Not in use: ${status.error}", 12.sp, NodeWeather.Cut.ink(ink.dark))
                                status.error != null -> Label("Update failed, using the last copy", 12.sp, NodeWeather.Mist.ink(ink.dark))
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f)) { Label(rule.value, 13.sp) }
                    }
                    Box(Modifier.width(70.dp)) {
                        val color = if (rule.action == "block") NodeWeather.Cut.ink(ink.dark) else ink.ink
                        Label(ACTIONS.firstOrNull { it.first == rule.action }?.second ?: rule.action, 13.sp, color, FontWeight.SemiBold)
                    }
                    // Fixed width, so every row's controls stand in line.
                    Row(Modifier.width(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        Small("↑", enabled = index > 0) { actions.onMoveRule(index, true) }
                        Small("↓", enabled = index < state.rules.lastIndex) { actions.onMoveRule(index, false) }
                        Small("×") { actions.onRemoveRule(index) }
                    }
                }
            }
        }
    }
}

/** A rule being written: its kind, its value, and where it goes. Enter or Add keeps it; Esc closes. */
@Composable
private fun NewRule(startKind: String, onAdd: (UserRule) -> Boolean, onCancel: () -> Unit) {
    val ink = LocalInk.current
    var kind by remember { mutableStateOf(startKind) }
    var action by remember { mutableStateOf("direct") }
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun add() {
        if (text.isBlank()) onCancel() else if (!onAdd(UserRule(kind, text, action))) wrong = true
    }
    Column(Modifier.fillMaxWidth().hairline(ink.hair, bottom = true).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            KINDS.forEach { (value, title) -> Choice(title, value == kind) { kind = value; wrong = false } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                text,
                { text = it; wrong = false },
                Modifier.weight(1f).height(36.dp).focusRequester(focus)
                    .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onCancel(); true } else false }
                    .hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.ink, bottom = true, width = 1.5.dp),
                singleLine = true,
                textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.mono, fontSize = 14.sp),
                cursorBrush = SolidColor(ink.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Label(HINTS[kind].orEmpty(), 14.sp, ink.soft)
                        field()
                    }
                },
            )
            ACTIONS.forEach { (value, title) -> Choice(title, value == action) { action = value } }
            Box(Modifier.height(34.dp).press(bleed = 8.dp) { add() }, contentAlignment = Alignment.Center) {
                Label("Add", 14.sp, weight = FontWeight.SemiBold)
            }
        }
        if (kind == "set") Catalog(text.trim().lowercase()) { text = it; wrong = false }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The common category lists by group, in pills; the one picked is filled in above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Catalog(picked: String, onPick: (String) -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (group in listOf(Categories.SERVICES, Categories.WEBSITES, Categories.REGIONS)) {
            Row {
                Box(Modifier.width(90.dp).padding(top = 6.dp)) { Label(group, 13.sp, ink.soft) }
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Categories.ALL.filter { it.group == group }.forEach { category ->
                        val on = category.name == picked
                        Box(
                            Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) ink.ink else ink.press)
                                .press { onPick(category.name) }.padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Label(category.title, 12.sp, if (on) ink.paper else ink.ink)
                        }
                    }
                }
            }
        }
        Label("Region lists match addresses; the others match names. A list is downloaded once and checked daily.", 12.sp, ink.soft)
    }
}


@Composable
private fun Small(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(Modifier.height(28.dp).width(28.dp).press(enabled = enabled, bleed = 2.dp) { if (enabled) onClick() }, contentAlignment = Alignment.Center) {
        Label(text, 14.sp, if (enabled) ink.soft else ink.hair)
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
