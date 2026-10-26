package cc.txwd.kone.ui

import cc.txwd.kone.i18n.tr
import cc.txwd.kone.i18n.countryTitle



import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

/**
 * A node as the dashboard shows it. Delay is the last test in milliseconds, null when it did not
 * answer; a node not tested yet rests (Z) rather than showing as cut off.
 */
data class Node(
    val id: String, val name: String, val country: String?, val category: String, val delay: Int?, val tested: Boolean = true,
    /** Imported by the person (⋮), and so theirs to take out. */
    val imported: Boolean = false,
    /** The kind of node as sing-box names it (vless, trojan, hysteria2…). */
    val protocol: String? = null,
    /** The share of its recent tests the node did not answer, 0 to 1; null before any. */
    val loss: Double? = null,
    /** Its last bandwidth test. */
    val speed: cc.txwd.kone.core.Speed? = null,
    /** Its recent tests, oldest first: a delay, or null for no answer (Nodes draws them as a line). */
    val history: List<Int?> = emptyList(),
) {
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
    /** One line on something that went well (Imported 3 nodes), for a few seconds. */
    val hint: String? = null,
    /** Whether the ⋮ menu's imports are there: once a key is in. */
    val canImport: Boolean = false,
    /** How soon this person's use ends, in its last week (3 days remaining); otherwise null. */
    val expiry: String? = null,
    /** Connected through Windows' proxy rather than KOne's adapter: only what follows the proxy goes through. */
    val proxied: Boolean = false,
    /** The node standing in while the chosen one is not answering, by name. */
    val standIn: String? = null,
    /** The last minute of rates, down and up in bytes a second, oldest first. */
    val trail: List<Pair<Long, Long>> = emptyList(),
    /** The network is holding traffic for its sign-in page (a hotel, an airport): shown with the way to sign in. */
    val signIn: SignInState? = null,
    /** Paused by the person, and when KOne comes back on ("14:35"). */
    val pausedUntil: String? = null,
) {
    val current: Node? get() = nodes.firstOrNull { it.id == selected }
}

/** A network's sign-in page, and whether KOne has stepped aside and is waiting for the person to sign in. */
data class SignInState(val page: String, val waiting: Boolean)

class DashboardActions(
    val onTab: (String) -> Unit = {},
    val onNode: (String) -> Unit = {},
    val onPower: () -> Unit = {},
    val onMenu: (MenuItem) -> Unit = {},
    /** Take out an imported node. */
    val onRemove: (String) -> Unit = {},
    val onSignIn: () -> Unit = {},
    /** Steps aside for a quarter of an hour, then connects again by itself. */
    val onPause: () -> Unit = {},
    val onCancelSignIn: () -> Unit = {},
)

enum class MenuItem(private val englishTitle: String) { Clipboard("Import from clipboard"), File("Import from file"), Screen("Scan a QR code on screen");
    val title: String get() = tr(englishTitle)
}

/**
 * Dashboard: the handscroll fills the page (the ink one, or with [cities] those of the node's country,
 * their skies as at [at], or now), mist rises over its foot, and on the mist sit the
 * categories, the nodes of the one chosen, and the status line with the power button.
 */
@Composable
fun DashboardView(state: DashboardState, actions: DashboardActions, menuOpen: Boolean, onMenuOpen: (Boolean) -> Unit, moving: Boolean = true, cities: Boolean = false, at: Instant? = null) {
    val ink = LocalInk.current
    Box(Modifier.fillMaxSize()) {
        if (cities) CityScroll(state.current?.country, moving = moving, at = at) else Handscroll(moving = moving)
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp).background(
                Brush.verticalGradient(0f to ink.veil.copy(alpha = 0f), 0.55f to ink.veil.copy(alpha = 0.84f), 1f to ink.paper),
            ),
        )
        Deck(state, actions, moving)
        if (state.canImport) More(menuOpen, onMenuOpen, actions.onMenu, backed = cities)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.Deck(state: DashboardState, actions: DashboardActions, moving: Boolean) {
    val ink = LocalInk.current
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.categories.forEach { tab ->
                val on = tab == state.tab
                Box(
                    Modifier.height(34.dp).press(bleed = 8.dp, role = Role.Tab, selected = on) { actions.onTab(tab) }
                        .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(countryTitle(tab), 14.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.nodes.filter { it.category == state.tab }.forEach { node ->
                val on = node.id == state.selected
                val color = if (on) ink.ink else ink.soft
                Row(
                    Modifier.height(44.dp).press(bleed = 8.dp, role = Role.RadioButton, selected = on) { actions.onNode(node.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Flag(node.country)
                    Label(node.name, 15.sp, color, if (on) FontWeight.SemiBold else FontWeight.Normal)
                    WeatherGlyph(node.weather)
                    Label(delayText(node.delay), 14.sp, color, tabular = true)
                    if (on) Glyph(Glyphs.check, color, 18.dp, strokeWidth = 2.2f)
                    if (on && node.imported) {
                        Box(Modifier.height(32.dp).press(bleed = 4.dp, label = tr("Remove {0}" , node.name)) { actions.onRemove(node.id) }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Label("×", 16.sp, ink.soft)
                        }
                    }
                }
            }
        }
        state.signIn?.let { SignInLine(it, actions) }
        StatusLine(state, actions.onPower, actions.onPause, moving)
    }
}

/** The network wants a sign-in: what is happening, in a line, and the one thing to do about it. */
@Composable
private fun SignInLine(signIn: SignInState, actions: DashboardActions) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().hairline(ink.hair, top = true).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Label(
            if (signIn.waiting) tr("Waiting for you to sign in; KOne connects again once the network is open")
            else tr("This network needs you to sign in before anything gets through"),
            14.sp, NodeWeather.Mist.ink(ink.dark),
        )
        Spacer(Modifier.weight(1f))
        if (signIn.waiting) {
            Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = actions.onCancelSignIn), contentAlignment = Alignment.Center) {
                Label(tr("Cancel"), 14.sp, ink.soft)
            }
        } else {
            Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = actions.onSignIn), contentAlignment = Alignment.Center) {
                Label(tr("Sign in"), 14.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatusLine(state: DashboardState, onPower: () -> Unit, onPause: () -> Unit = {}, moving: Boolean = true) {
    val ink = LocalInk.current
    val still = LocalStill.current
    Row(
        Modifier.fillMaxWidth().hairline(ink.hair, top = true).padding(top = 14.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // What the connection is, in the room the buttons leave: a long exit or category ends in …
        // rather than pushing Disconnect off the window. Connected and not, the two give way softly.
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                state, contentKey = { it.connection == Connection.Online },
                transitionSpec = { fadeIn(Motion.spec(still, Motion.STANDARD)) togetherWith fadeOut(Motion.spec(still, Motion.QUICK)) },
                label = "status",
            ) { shown -> ConnectionInfo(shown) }
        }
        // A quarter of an hour without KOne (a bank's app, a sign-in elsewhere), then back by itself.
        if (state.connection == Connection.Online) {
            Box(Modifier.height(48.dp).press(bleed = 6.dp, onClick = onPause).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Label(tr("Pause 15 min"), 14.sp, ink.soft)
            }
        }
        Row(
            Modifier.height(52.dp).press(bleed = 6.dp, onClick = onPower)
                .semantics { stateDescription = when (state.connection) { Connection.Online -> tr("Connected"); Connection.Connecting -> tr("Connecting"); Connection.Idle -> tr("Disconnected") } }
                .padding(start = 2.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The ring says the connection's state at a glance; the word says what a press does.
            Box(contentAlignment = Alignment.Center) {
                ConnectionRing(state.connection, state.current?.weather ?: NodeWeather.Rest, 44.dp, moving)
                Glyph(Glyphs.power, ink.ink, 18.dp, strokeWidth = 2f)
            }
            AnimatedContent(
                when (state.connection) {
                    Connection.Online -> tr("Disconnect")
                    Connection.Connecting -> tr("Connecting")
                    Connection.Idle -> tr("Connect")
                },
                transitionSpec = { fadeIn(Motion.spec(still, Motion.STANDARD)) togetherWith fadeOut(Motion.spec(still, Motion.QUICK)) },
                label = "power",
            ) { word -> Label(word, 18.sp, weight = FontWeight.SemiBold) }
        }
    }
}

/** The node, its exit and its figures while connected; otherwise its weather and the last word on what happened. */
@Composable
private fun ConnectionInfo(state: DashboardState) {
    val ink = LocalInk.current
    val node = state.current
    if (node != null) {
        val weather = node.weather
        if (state.connection == Connection.Online) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Flag(node.country, 26.dp, 18.dp)
                    Label(tr("Exit {0}" , state.exit ?: "—"), 18.sp, weight = FontWeight.SemiBold, ellipsis = true)
                    Label(countryTitle(node.category), 18.sp, ink.soft, modifier = Modifier.weight(1f, fill = false), ellipsis = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WeatherTag(weather)
                        Label(delayText(node.delay), 14.sp, ink.ink, tabular = true)
                    }
                    if (state.home) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Glyph(Glyphs.home, ink.soft, 16.dp, strokeWidth = 1.8f)
                            Label(tr("Home"), 14.sp, ink.soft)
                        }
                    }
                    if (state.proxied) Label(tr("System proxy"), 14.sp, ink.soft)
                    Label("↓ ${rate(state.downKbps)}　↑ ${rate(state.upKbps)}", 14.sp, ink.soft, tabular = true)
                    if (state.trail.size >= 2) Sparkline(state.trail)
                    Label(clock(state.seconds), 14.sp, ink.soft, tabular = true)
                    state.expiry?.let { Label(it, 14.sp, NodeWeather.Mist.ink(ink.dark)) }
                    state.standIn?.let { Label(tr("Using {0} for now" , it), 14.sp, NodeWeather.Mist.ink(ink.dark)) }
                    state.hint?.let { Label(it, 14.sp, ink.soft) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Flag(node.country, 26.dp, 18.dp)
                    WeatherTag(weather)
                    Label(delayText(node.delay), 18.sp, weight = FontWeight.SemiBold, tabular = true)
                }
                when {
                    state.notice != null -> Label(state.notice, 14.sp, NodeWeather.Cut.ink(ink.dark), ellipsis = true)
                    state.pausedUntil != null -> Label(tr("Paused until {0}; Connect to come back now" , state.pausedUntil), 14.sp, ink.soft, ellipsis = true)
                    state.hint != null -> Label(state.hint, 14.sp, ink.soft, ellipsis = true)
                    state.expiry != null -> Label(state.expiry, 14.sp, NodeWeather.Mist.ink(ink.dark), ellipsis = true)
                }
            }
        }
    } else {
        state.notice?.let { Label(it, 14.sp, NodeWeather.Cut.ink(ink.dark), ellipsis = true) }
    }
}

/** The ⋮ in the corner and its menu; [backed] sets the dots on a disc of paper, for a painting that may be light or dark under them. */
@Composable
private fun BoxScope.More(open: Boolean, onOpen: (Boolean) -> Unit, onPick: (MenuItem) -> Unit, backed: Boolean = false) {
    val ink = LocalInk.current
    // The press that closes the menu by clicking the dots must not open it again (see MenuState).
    val closed = remember { longArrayOf(0L) }
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).then(if (backed) Modifier.background(ink.paper.copy(alpha = 0.72f)) else Modifier)
                .press(label = tr("Import nodes")) { if (open || System.currentTimeMillis() - closed[0] > 300) onOpen(!open) },
            contentAlignment = Alignment.Center,
        ) {
            MoreDots(ink.ink)
        }
        InkMenu(open, { if (open) closed[0] = System.currentTimeMillis(); onOpen(false) }, MenuItem.entries.map { item -> item.title to { onPick(item) } }, below = 44.dp)
    }
}

/** The last minute of traffic as two thin lines of ink, down above up, scaled to the busiest second. */
@Composable
internal fun Sparkline(trail: List<Pair<Long, Long>>, modifier: Modifier = Modifier.size(84.dp, 20.dp)) {
    val ink = LocalInk.current
    androidx.compose.foundation.Canvas(modifier) {
        val most = trail.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1024)
        val step = size.width / 59f
        val start = size.width - step * (trail.size - 1)
        fun line(pick: (Pair<Long, Long>) -> Long, color: Color, width: Float) {
            val path = androidx.compose.ui.graphics.Path()
            trail.forEachIndexed { i, rates ->
                val x = start + i * step
                val y = size.height - 1f - (pick(rates).toFloat() / most) * (size.height - 2f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
        }
        line({ it.second }, ink.soft.copy(alpha = 0.6f), 1.dp.toPx())
        line({ it.first }, ink.ink, 1.4.dp.toPx())
    }
}

fun delayText(delay: Int?): String = if (delay == null) "—" else "$delay ms"

fun rate(kbps: Double): String = "%.1f KB/s".format(java.util.Locale.ROOT, kbps)

fun clock(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
