package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A node as the dashboard shows it. Delay is the last test in milliseconds, null when it did not
 * answer; a node not tested yet rests (moon) rather than showing as cut off.
 */
data class Node(val id: String, val name: String, val country: String?, val category: String, val delay: Int?, val tested: Boolean = true) {
    val weather: NodeWeather get() = if (tested) NodeWeather.of(delay) else NodeWeather.Rest
}

enum class Connection { Idle, Connecting, Online }

/** Everything the dashboard draws; the App fills it from the console and the core. */
data class DashboardState(
    val categories: List<String>,
    val nodes: List<Node>,
    val tab: String,
    val selected: String?,
    val connection: Connection = Connection.Idle,
    /** The exit address while connected, as the node reports it. */
    val exit: String? = null,
    /** Whether Home (Tailscale) is on; shown only when it is. */
    val home: Boolean = false,
    val downKbps: Double = 0.0,
    val upKbps: Double = 0.0,
    val seconds: Long = 0,
    /** One line about what just went wrong (the prompt declined, the service paused), until the next try. */
    val notice: String? = null,
    /** Whether the ⋮ menu's imports are there; hidden while the App has none. */
    val canImport: Boolean = false,
) {
    val current: Node? get() = nodes.firstOrNull { it.id == selected }
}

class DashboardActions(
    val onTab: (String) -> Unit = {},
    val onNode: (String) -> Unit = {},
    val onPower: () -> Unit = {},
    val onMenu: (MenuItem) -> Unit = {},
)

enum class MenuItem(val title: String) { Clipboard("Import from clipboard"), File("Import from file"), Manual("Add manually"), Category("Add category") }

/**
 * Dashboard: the handscroll fills the page, mist rises over its foot, and on the mist sit the
 * categories, the nodes of the one chosen, and the status line with the power button.
 */
@Composable
fun DashboardView(state: DashboardState, actions: DashboardActions, menuOpen: Boolean, onMenuOpen: (Boolean) -> Unit, moving: Boolean = true) {
    val ink = LocalInk.current
    Box(Modifier.fillMaxSize()) {
        Handscroll(moving = moving)
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp).background(
                Brush.verticalGradient(0f to ink.veil.copy(alpha = 0f), 0.55f to ink.veil.copy(alpha = 0.84f), 1f to ink.paper),
            ),
        )
        Deck(state, actions)
        if (state.canImport) More(menuOpen, onMenuOpen, actions.onMenu)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.Deck(state: DashboardState, actions: DashboardActions) {
    val ink = LocalInk.current
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.categories.forEach { tab ->
                val on = tab == state.tab
                Box(
                    Modifier.height(34.dp).press(bleed = 8.dp) { actions.onTab(tab) }
                        .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(tab, 14.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.nodes.filter { it.category == state.tab }.forEach { node ->
                val on = node.id == state.selected
                val color = if (on) ink.ink else ink.soft
                Row(
                    Modifier.height(44.dp).press(bleed = 8.dp) { actions.onNode(node.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Flag(node.country)
                    Label(node.name, 15.sp, color, if (on) FontWeight.SemiBold else FontWeight.Normal)
                    WeatherGlyph(node.weather)
                    Label(delayText(node.delay), 14.sp, color, tabular = true)
                    if (on) Glyph(Glyphs.check, color, 18.dp, strokeWidth = 2.2f)
                }
            }
        }
        StatusLine(state, actions.onPower)
    }
}

@Composable
private fun StatusLine(state: DashboardState, onPower: () -> Unit) {
    val ink = LocalInk.current
    val node = state.current
    Row(
        Modifier.fillMaxWidth().hairline(ink.hair, top = true).padding(top = 14.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node != null) {
            val weather = node.weather
            if (state.connection == Connection.Online) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Flag(node.country, 26.dp, 18.dp)
                        Label("Exit ${state.exit ?: "—"}", 18.sp, weight = FontWeight.SemiBold)
                        Label(node.category, 18.sp, ink.soft)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WeatherTag(weather)
                            Label(delayText(node.delay), 14.sp, ink.ink, tabular = true)
                        }
                        if (state.home) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Glyph(Glyphs.home, ink.soft, 16.dp, strokeWidth = 1.8f)
                                Label("Home", 14.sp, ink.soft)
                            }
                        }
                        Label("↓ ${rate(state.downKbps)}　↑ ${rate(state.upKbps)}", 14.sp, ink.soft, tabular = true)
                        Label(clock(state.seconds), 14.sp, ink.soft, tabular = true)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Flag(node.country, 26.dp, 18.dp)
                        WeatherTag(weather)
                        Label(delayText(node.delay), 18.sp, weight = FontWeight.SemiBold, tabular = true)
                    }
                    state.notice?.let { Label(it, 14.sp, NodeWeather.Cut.ink(ink.dark)) }
                }
            }
        } else {
            state.notice?.let { Label(it, 14.sp, NodeWeather.Cut.ink(ink.dark)) }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(48.dp).press(enabled = state.connection != Connection.Connecting, bleed = 6.dp, onClick = onPower).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Glyph(Glyphs.power, ink.ink, 22.dp, strokeWidth = 2f)
            Label(
                when (state.connection) {
                    Connection.Online -> "Close"
                    Connection.Connecting -> "Connecting"
                    Connection.Idle -> "Connect"
                },
                18.sp, weight = FontWeight.SemiBold,
            )
        }
    }
}

/** The ⋮ in the corner and its menu. */
@Composable
private fun BoxScope.More(open: Boolean, onOpen: (Boolean) -> Unit, onPick: (MenuItem) -> Unit) {
    val ink = LocalInk.current
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).press { onOpen(!open) }, contentAlignment = Alignment.Center) {
            MoreDots(ink.ink)
        }
        if (open) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.widthIn(min = 168.dp).shadow(14.dp, shape, ambientColor = Color(0x2E141C19), spotColor = Color(0x2E141C19))
                    .background(ink.menu, shape).padding(6.dp),
            ) {
                MenuItem.entries.forEach { item ->
                    Box(
                        Modifier.width(156.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).press { onOpen(false); onPick(item) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Label(item.title, 14.sp) }
                }
            }
        }
    }
}

fun delayText(delay: Int?): String = if (delay == null) "—" else "$delay ms"

fun rate(kbps: Double): String = "%.1f KB/s".format(java.util.Locale.ROOT, kbps)

fun clock(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
