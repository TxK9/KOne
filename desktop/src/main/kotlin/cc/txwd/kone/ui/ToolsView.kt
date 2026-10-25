package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import cc.txwd.kone.app.Check
import cc.txwd.kone.app.ConnectionWatch
import cc.txwd.kone.app.SiteCheck
import cc.txwd.kone.app.Outcome
import cc.txwd.kone.app.Used
import cc.txwd.kone.core.Carried

/** Tools as the App fills it: the mode, the way KOne connects, usage, and what the core carries while connected. */
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
    /** The last diagnosis, step by step; empty until one is run. */
    val checks: List<Check> = emptyList(),
    val checking: Boolean = false,
    /** One suggestion for the first check that failed. */
    val advice: String? = null,
    /** The last DNS lookup: the name, each way it was asked, and what they add up to. */
    val lookup: DnsLookupState? = null,
    /** Usage by node, by program and by day, while the person has it open. */
    val details: UsageDetails? = null,
    /** The last site check: the site, each way it was tried, and which way to keep. */
    val site: SiteCheckState? = null,
    /** Sites real traffic shows failing straight out, to send through the node. */
    val siteAdvice: List<ConnectionWatch.Advice> = emptyList(),
)

data class SiteCheckState(val site: String, val checks: List<Check>, val running: Boolean, val verdict: String? = null, val way: SiteCheck.Way? = null)

/** Over [span] days (1 is today): each node's and program's share, largest first, and each day's total. */
data class UsageDetails(
    val span: Int,
    val nodes: List<Pair<String, Used>>,
    val apps: List<Pair<String, Used>>,
    val daily: List<Pair<java.time.LocalDate, Used>>,
)

data class DnsLookupState(val name: String, val checks: List<Check>, val running: Boolean, val verdict: String? = null)

class ToolsActions(
    val onMode: (String) -> Unit = {},
    val onWay: (String) -> Unit = {},
    /** A program sent straight out, from the connection list (Rules keeps the list). */
    val onBypass: (String, Boolean) -> Unit = { _, _ -> },
    /** End one connection, or all of them for null. */
    val onClose: (String?) -> Unit = {},
    val onDiagnose: () -> Unit = {},
    /** Copies the last diagnosis as plain text, for whoever helps. */
    val onCopyReport: () -> Unit = {},
    /** Puts the last diagnosis away, giving its room back to the connections. */
    val onClearChecks: () -> Unit = {},
    /** Looks a name up every way KOne can; false when it is not a domain name. */
    val onLookUp: (String) -> Boolean = { true },
    val onClearLookup: () -> Unit = {},
    /** Shows usage over 1, 7 or 30 days, or puts it away for null. */
    val onUsage: (Int?) -> Unit = {},
    /** Tries a site straight out and through nodes; false when it is not a domain name. */
    val onCheckSite: (String) -> Boolean = { true },
    /** Keeps a site check's answer: straight out (true) or through the node. */
    val onKeepSite: (Boolean) -> Unit = {},
    val onClearSite: () -> Unit = {},
    /** Sends a connection's site straight out (true) or through the node from now on. */
    val onSendSite: (String, Boolean) -> Unit = { _, _ -> },
    /** Sends an advised site through the node, or lets the advice go. */
    val onTakeAdvice: (String) -> Unit = {},
    val onDismissAdvice: (String) -> Unit = {},
)

private val MODE_NAMES = listOf("Rule" to "Rule", "Global" to "Global", "Direct" to "Direct")
private val WAY_NAMES = listOf("Adapter" to "Virtual adapter", "Proxy" to "System proxy")

/** Tools: Mode, Connection method, Usage, and while connected each connection, with the program behind it. */
@Composable
fun ToolsView(state: ToolsState, actions: ToolsActions) {
    val ink = LocalInk.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
    // While connected the settings above keep at most three fifths of the page, scrolling within it, so the connections always show.
    val top = if (state.carried != null) maxHeight * 0.6f else maxHeight
    Column(Modifier.fillMaxSize()) {
        val upper = rememberScrollState()
        Box(Modifier.widthIn(max = 640.dp + ROOM).fillMaxWidth().heightIn(max = top).fadingEdges(upper, ink.paper)) {
        Column(Modifier.fillMaxWidth().verticalScroll(upper).padding(end = ROOM)) {
            state.siteAdvice.forEach { Advised(it, actions) }
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
                    Spacer(Modifier.width(22.dp))
                    val open = state.details != null
                    Box(Modifier.height(34.dp).press(bleed = 8.dp) { actions.onUsage(if (open) null else 1) }, contentAlignment = Alignment.Center) {
                        Label(if (open) "Hide" else "Details", 14.sp, weight = FontWeight.SemiBold)
                    }
                }
                state.details?.let { UsagePanel(it, actions.onUsage) }
            }
            Line("Diagnose") {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (state.checks.isNotEmpty() && !state.checking) {
                        Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = actions.onCopyReport), contentAlignment = Alignment.Center) {
                            Label("Copy report", 14.sp, ink.soft)
                        }
                        Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = actions.onClearChecks), contentAlignment = Alignment.Center) {
                            Label("Clear", 14.sp, ink.soft)
                        }
                    }
                    Box(Modifier.height(34.dp).press(enabled = !state.checking, bleed = 8.dp) { if (!state.checking) actions.onDiagnose() }, contentAlignment = Alignment.Center) {
                        if (state.checking) Busy("Checking") else Label("Run", 14.sp, ink.ink, FontWeight.SemiBold)
                    }
                }
            }
            if (state.checks.isNotEmpty()) Checks(state.checks, state.advice)
            Lookup(state.lookup, actions)
            SiteLine(state.site, actions)
        }
        InkScrollbar(upper, Modifier.align(Alignment.TopEnd))
        }
        state.carried?.let { carried -> Connections(carried, state.bypass, actions) }
    }
    }
}

private val SPANS = listOf(1 to "Today", 7 to "7 days", 30 to "30 days")

/** Usage in detail: the span, each day's total as a bar, then by node and by program side by side. */
@Composable
private fun UsagePanel(details: UsageDetails, onSpan: (Int?) -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).hairline(ink.hair, bottom = true).padding(bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SPANS.forEach { (span, name) -> Choice(name, span == details.span) { onSpan(span) } }
        }
        if (details.span > 1) Days(details.daily)
        if (details.nodes.isEmpty()) {
            Label("Counted by node and program while KOne is connected", 13.sp, ink.soft, modifier = Modifier.padding(top = 10.dp))
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Shares("By node", details.nodes, Modifier.weight(1f))
            Shares("By program", details.apps, Modifier.weight(1f))
        }
    }
}

/** Each day's total as a thin bar, today darkest; the first and last dates beneath. */
@Composable
private fun Days(daily: List<Pair<java.time.LocalDate, Used>>) {
    val ink = LocalInk.current
    if (daily.isEmpty()) return
    val most = daily.maxOf { it.second.total }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(44.dp).drawBehind {
                val step = size.width / daily.size
                val bar = (step * 0.6f).coerceAtMost(14.dp.toPx())
                daily.forEachIndexed { i, (_, used) ->
                    val h = (size.height * used.total / most).coerceAtLeast(if (used.total > 0) 1.dp.toPx() else 0f)
                    drawRect(
                        if (i == daily.lastIndex) ink.ink else ink.soft.copy(alpha = 0.55f),
                        Offset(i * step + (step - bar) / 2, size.height - h), androidx.compose.ui.geometry.Size(bar, h),
                    )
                }
                drawRect(ink.hair, Offset(0f, size.height - 1f), androidx.compose.ui.geometry.Size(size.width, 1f))
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Label(daily.first().first.toString().substring(5), 11.sp, ink.soft, tabular = true)
            Spacer(Modifier.weight(1f))
            Label("Most ${size(most)} a day", 11.sp, ink.soft, tabular = true)
            Spacer(Modifier.weight(1f))
            Label("Today", 11.sp, ink.soft)
        }
    }
}

/** The largest few, each with its total, down and up, and a thin bar for its share of the whole. */
@Composable
private fun Shares(title: String, list: List<Pair<String, Used>>, modifier: Modifier) {
    val ink = LocalInk.current
    val whole = list.sumOf { it.second.total }.coerceAtLeast(1)
    Column(modifier) {
        Label(title, 13.sp, ink.soft, modifier = Modifier.padding(bottom = 4.dp))
        list.take(SHOWN).forEach { (name, used) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        name, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = TextStyle(color = ink.ink, fontSize = 13.sp, fontFamily = Fonts.ui),
                    )
                    Spacer(Modifier.width(10.dp))
                    Label("↓ ${size(used.down)}  ↑ ${size(used.up)}", 11.sp, ink.soft, tabular = true)
                    Row(Modifier.width(76.dp), horizontalArrangement = Arrangement.End) { Label(size(used.total), 13.sp, tabular = true) }
                }
                val share = used.total.toFloat() / whole
                Box(
                    Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp).drawBehind {
                        drawRect(ink.hair)
                        drawRect(ink.soft, size = androidx.compose.ui.geometry.Size(size.width * share, size.height))
                    },
                )
            }
        }
        if (list.size > SHOWN) {
            val rest = list.drop(SHOWN).sumOf { it.second.total }
            Label("${list.size - SHOWN} more · ${size(rest)}", 12.sp, ink.soft, tabular = true, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private const val SHOWN = 6

/** A site real traffic shows failing straight out: what was seen, and the two answers. */
@Composable
private fun Advised(advice: ConnectionWatch.Advice, actions: ToolsActions) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).hairline(ink.hair, bottom = true),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Label("${advice.site} does not answer straight out", 14.sp, NodeWeather.Mist.ink(ink.dark), FontWeight.SemiBold)
            Label("${advice.failures} tries in the last minutes got nothing back", 12.sp, ink.soft)
        }
        Box(Modifier.height(34.dp).press(bleed = 8.dp) { actions.onDismissAdvice(advice.site) }, contentAlignment = Alignment.Center) {
            Label("Not now", 14.sp, ink.soft)
        }
        Box(Modifier.height(34.dp).press(bleed = 8.dp) { actions.onTakeAdvice(advice.site) }, contentAlignment = Alignment.Center) {
            Label("Send through the node", 14.sp, weight = FontWeight.SemiBold)
        }
    }
}

/** DNS lookup: a name, then one row for each way it was asked, and a sentence on what they add up to. */
@Composable
private fun Lookup(lookup: DnsLookupState?, actions: ToolsActions) {
    Ask("DNS lookup", "Look up", "Looking up", lookup?.name, lookup?.running == true, lookup != null, actions.onLookUp, actions.onClearLookup)
    if (lookup != null && lookup.checks.isNotEmpty()) Checks(lookup.checks, lookup.verdict, verdict = true)
}

/** Site check: a site tried each way, a sentence on which serves it best, and the one press that keeps it. */
@Composable
private fun SiteLine(site: SiteCheckState?, actions: ToolsActions) {
    Ask("Site check", "Check", "Checking", site?.site, site?.running == true, site != null, actions.onCheckSite, actions.onClearSite)
    if (site == null || (site.checks.isEmpty() && site.verdict == null)) return
    if (site.checks.isNotEmpty()) Checks(site.checks, site.verdict, verdict = true) else Label(site.verdict.orEmpty(), 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
    val keep = when (site.way) {
        SiteCheck.Way.Direct -> true to "Always send ${site.site} straight out"
        SiteCheck.Way.Proxy -> false to "Always send ${site.site} through the node"
        else -> null
    }
    keep?.let { (direct, title) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Box(Modifier.height(34.dp).press(bleed = 8.dp) { actions.onKeepSite(direct) }, contentAlignment = Alignment.Center) {
                Label(title, 14.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** A line that asks about a name: the field (Enter asks too), Clear once there is an answer, and the button. */
@Composable
private fun Ask(title: String, action: String, busy: String, asked: String?, running: Boolean, answered: Boolean, onAsk: (String) -> Boolean, onClear: () -> Unit) {
    val ink = LocalInk.current
    var name by remember { mutableStateOf(asked.orEmpty()) }
    var wrong by remember { mutableStateOf(false) }
    fun go() {
        if (!running) wrong = !onAsk(name)
    }
    Line(title) {
        BasicTextField(
            name, { name = it; wrong = false },
            Modifier.width(220.dp).height(30.dp).hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.hair, bottom = true, width = 1.5.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { go(); true } else false
                },
            singleLine = true,
            textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.ui, fontSize = 13.sp),
            cursorBrush = SolidColor(ink.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) Label("example.com", 13.sp, ink.soft)
                    field()
                }
            },
        )
        Spacer(Modifier.width(22.dp))
        if (answered && !running) {
            Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onClear), contentAlignment = Alignment.Center) {
                Label("Clear", 14.sp, ink.soft)
            }
            Spacer(Modifier.width(22.dp))
        }
        Box(Modifier.height(34.dp).press(enabled = !running, bleed = 8.dp) { go() }, contentAlignment = Alignment.Center) {
            if (running) Busy(busy) else Label(action, 14.sp, ink.ink, FontWeight.SemiBold)
        }
    }
    if (wrong) Label("Enter a domain name, such as example.com", 13.sp, NodeWeather.Cut.ink(ink.dark), modifier = Modifier.padding(top = 6.dp))
}

/** A diagnosis, one row per check: what, how it went, and a few words; then the suggestion (or, for a lookup, the verdict). */
@Composable
private fun Checks(checks: List<Check>, advice: String?, verdict: Boolean = false) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).hairline(ink.hair, bottom = true).padding(bottom = 8.dp)) {
        checks.forEach { check ->
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(90.dp)) { Label(check.title, 13.sp, ink.soft) }
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    when (check.outcome) {
                        Outcome.Running -> Label("…", 13.sp, ink.soft)
                        Outcome.Pass -> Glyph(Glyphs.check, NodeWeather.Clear.ink(ink.dark), 15.dp, strokeWidth = 2.2f)
                        Outcome.Warn -> Label("!", 14.sp, NodeWeather.Mist.ink(ink.dark), FontWeight.SemiBold)
                        Outcome.Fail -> Label("×", 15.sp, NodeWeather.Cut.ink(ink.dark), FontWeight.SemiBold)
                    }
                }
                Label(check.detail, 13.sp, if (check.outcome == Outcome.Fail) NodeWeather.Cut.ink(ink.dark) else ink.ink, tabular = true)
            }
        }
        advice?.let { Label(it, 13.sp, if (verdict) ink.ink else NodeWeather.Mist.ink(ink.dark), FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** How the connection list is ordered: newest first, or by bytes down or up. */
private enum class Order(val title: String) { Recent("Recent"), Down("↓"), Up("↑") }

@Composable
private fun ColumnScope.Connections(carried: Carried, bypass: List<String>, actions: ToolsActions) {
    val ink = LocalInk.current
    var query by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(Order.Recent) }
    val needle = query.trim()
    val shown = carried.connections
        .filter { needle.isEmpty() || listOfNotNull(it.program, it.target, it.via, it.why).any { field -> field.contains(needle, ignoreCase = true) } }
        .let { list ->
            when (order) {
                Order.Recent -> list
                Order.Down -> list.sortedByDescending { it.down }
                Order.Up -> list.sortedByDescending { it.up }
            }
        }
    // Its right edge in line with the rows below, which leave the scrollbar its room.
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, end = ROOM).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Connections", 15.sp)
        Spacer(Modifier.width(14.dp))
        Label(if (needle.isEmpty()) "${carried.connections.size}" else "${shown.size} / ${carried.connections.size}", 15.sp, ink.soft, tabular = true)
        Spacer(Modifier.width(22.dp))
        Label("↓ ${size(carried.down)}　↑ ${size(carried.up)}", 13.sp, ink.soft, tabular = true)
        Spacer(Modifier.width(28.dp))
        BasicTextField(
            query, { query = it },
            Modifier.width(180.dp).height(30.dp).hairline(ink.hair, bottom = true, width = 1.5.dp),
            singleLine = true,
            textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.ui, fontSize = 13.sp),
            cursorBrush = SolidColor(ink.ink),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Label("App, address or node", 13.sp, ink.soft)
                    field()
                }
            },
        )
        Spacer(Modifier.width(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Order.entries.forEach { choice -> Choice(choice.title, choice == order) { order = choice } }
        }
        Spacer(Modifier.weight(1f))
        if (carried.connections.isNotEmpty()) {
            Box(Modifier.height(34.dp).press(bleed = 8.dp) { actions.onClose(null) }, contentAlignment = Alignment.Center) {
                Label("Disconnect all", 14.sp, weight = FontWeight.SemiBold)
            }
        }
    }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxWidth().weight(1f).fadingEdges(list, LocalInk.current.paper)) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(end = ROOM)) {
            items(shown, key = { it.id }) { connection ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).hairline(ink.hair, bottom = true).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.width(170.dp)) { Wrapped(connection.program ?: "—", 13.sp, ink.ink) }
                    // The site, and the one press that sends it the other way from now on (Rules' site lists).
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f, fill = false)) { Wrapped(connection.target, 13.sp, ink.ink) }
                        val site = cc.txwd.kone.core.KoneConfig.site(connection.target)?.removePrefix("www.")
                        if (site != null) {
                            val straight = connection.via != "direct"
                            Box(Modifier.height(28.dp).press(bleed = 4.dp) { actions.onSendSite(site, straight) }, contentAlignment = Alignment.Center) {
                                Label(if (straight) "Straight out" else "Via node", 11.sp, ink.soft)
                            }
                        }
                    }
                    // The way it went, and beneath it why: which list, rule, category or mode sent it there.
                    Column(Modifier.width(150.dp)) {
                        Wrapped(if (connection.via == "direct") "Direct" else connection.via, 13.sp, ink.soft)
                        connection.why?.let { Wrapped(it, 11.sp, ink.soft.copy(alpha = 0.75f)) }
                    }
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
        if (shown.isEmpty()) {
            if (carried.connections.isEmpty()) EmptyState("No connections right now", "Programs' connections through KOne appear here as they open.")
            else EmptyState("Nothing matches", "Try a program's name, an address or a node.")
        }
        InkScrollbar(list, Modifier.align(Alignment.TopEnd))
    }
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

/** Bytes as people read them: 812 B, 3.4 KB, 12.6 MB, 1.20 GB. */
fun size(bytes: Long): String = cc.txwd.kone.shared.UserInfo.size(bytes)
