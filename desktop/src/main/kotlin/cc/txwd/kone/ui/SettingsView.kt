package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.txwd.kone.app.Hotkey

/** Appearance: as Windows is set, or always light, or always dark. */
enum class Appearance(val title: String) { System("System default"), Light("Light"), Dark("Dark") }

/** Background: KOne's own ink handscroll, or the cities of the node's country (Codex's scenes). */
enum class Backdrop(val title: String) { Ink("Ink"), City("Cities") }

/** Where updating stands, as Settings shows it beside the version. */
sealed interface UpdateState {
    /** This build looks nowhere for updates: the version alone. */
    data object Off : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Latest : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

data class SettingsState(
    val appearance: Appearance,
    val backdrop: Backdrop = Backdrop.Ink,
    /** The cities' wind, rain and sea heard; offered only with Cities. */
    val sound: Boolean = false,
    /** Whether KOne starts with Windows; null where it cannot (not installed), and the row is left out. */
    val autostart: Boolean?,
    /** Whether Ctrl+Alt+K turns the connection on and off; null where it cannot (not Windows), and the row is left out. */
    val hotkey: Boolean? = null,
    /** Another program has Ctrl+Alt+K. */
    val hotkeyTaken: Boolean = false,
    val version: String,
    val update: UpdateState,
)

class SettingsActions(
    val onAppearance: (Appearance) -> Unit = {},
    val onBackdrop: (Backdrop) -> Unit = {},
    val onSound: (Boolean) -> Unit = {},
    val onAutostart: (Boolean) -> Unit = {},
    val onHotkey: (Boolean) -> Unit = {},
    val onCheck: () -> Unit = {},
    val onUpdate: () -> Unit = {},
)

/** How names are answered: the DNS server through the node, the one straight out, and IPv6. */
data class NetworkState(
    val remoteDns: String = "", val directDns: String = "", val ipv6: Boolean = false,
    /** Whether a chosen node that stops answering is stood in for by the best working one. */
    val failover: Boolean = true,
    /** Fixed answers for chosen names. */
    val hosts: List<cc.txwd.kone.core.Host> = emptyList(),
    /** Servers of the person's own for chosen domains. */
    val nameServers: List<cc.txwd.kone.core.NameServer> = emptyList(),
    /** Where the person is: outside China ("global") or China. */
    val region: String = cc.txwd.kone.shared.Region.GLOBAL,
)

class NetworkActions(
    /** A remote DNS choice: a preset's name or a DNS-over-HTTPS address; false when it is not one. */
    val onRemoteDns: (String) -> Boolean = { true },
    /** A direct DNS choice: a preset's name or an address; false when it is not one. */
    val onDirectDns: (String) -> Boolean = { true },
    val onIpv6: (Boolean) -> Unit = {},
    val onFailover: (Boolean) -> Unit = {},
    /** A fixed answer typed as "name address"; false when it is not one. */
    val onAddHost: (String) -> Boolean = { true },
    val onRemoveHost: (cc.txwd.kone.core.Host) -> Unit = {},
    /** A server for a domain typed as "domain server"; false when it is not one. */
    val onAddNameServer: (String) -> Boolean = { true },
    val onRemoveNameServer: (cc.txwd.kone.core.NameServer) -> Unit = {},
    val onRegion: (String) -> Unit = {},
    /** Saves the setup to a file the person picks, or reads one back. */
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
)

private val REMOTE_DNS = listOf("" to "Cloudflare", "google" to "Google", "quad9" to "Quad9")
/** The direct presets in each region, the region's default ("") first. */
private fun directDns(region: String) =
    if (cc.txwd.kone.shared.Region.china(region)) listOf("" to "AliDNS", "dnspod" to "DNSPod", "cloudflare" to "Cloudflare")
    else listOf("" to "Cloudflare", "google" to "Google", "quad9" to "Quad9")

/**
 * Settings: Appearance, Background (with Ambient sound for the cities), Start with Windows,
 * Keyboard shortcut, the DNS servers and IPv6, and the version with its update, one line each on the paper.
 */
@Composable
fun SettingsView(state: SettingsState, actions: SettingsActions, network: NetworkState = NetworkState(), networkActions: NetworkActions = NetworkActions()) {
    val ink = LocalInk.current
    // Scrolls when the window is too short for every line.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 26.dp).widthIn(max = 640.dp).fillMaxWidth()) {
        Line("Appearance") { Choices(Appearance.entries, state.appearance, Appearance::title, actions.onAppearance) }
        Line("Background") { Choices(Backdrop.entries, state.backdrop, Backdrop::title, actions.onBackdrop) }
        if (state.backdrop == Backdrop.City) Line("Ambient sound") { Switch(state.sound) { actions.onSound(!state.sound) } }
        state.autostart?.let { on ->
            Line("Start with Windows") { Switch(on) { actions.onAutostart(!on) } }
        }
        state.hotkey?.let { on ->
            Line("Keyboard shortcut") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (state.hotkeyTaken) Label("${Hotkey.KEYS} is already in use", 14.sp, NodeWeather.Cut.ink(ink.dark))
                    else Label(Hotkey.KEYS, 14.sp, ink.soft)
                    Switch(on) { actions.onHotkey(!on) }
                }
            }
        }
        // Where the person is decides the China split and which servers are offered straight out.
        Line("Where you are") {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                val china = cc.txwd.kone.shared.Region.china(network.region)
                Choice("Outside China", !china) { networkActions.onRegion(cc.txwd.kone.shared.Region.GLOBAL) }
                Choice("China", china) { networkActions.onRegion(cc.txwd.kone.shared.Region.CHINA) }
            }
        }
        DnsLine("Remote DNS", REMOTE_DNS, network.remoteDns, "https://dns.example.com/dns-query", networkActions.onRemoteDns)
        DnsLine("Direct DNS", directDns(network.region), network.directDns, "203.0.113.53", networkActions.onDirectDns)
        PairsLine("Hosts", network.hosts, { "${it.name}  ${it.address}" }, networkActions.onRemoveHost, networkActions.onAddHost, "nas.example.org 192.0.2.10")
        PairsLine("DNS for domains", network.nameServers, { "${it.domain}  ${it.server}" }, networkActions.onRemoveNameServer, networkActions.onAddNameServer, "corp.example.org 192.0.2.53")
        Line("IPv6") { Switch(network.ipv6) { networkActions.onIpv6(!network.ipv6) } }
        Line("Fail over") { Switch(network.failover) { networkActions.onFailover(!network.failover) } }
        Line("Backup") {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Action("Export", networkActions.onExport)
                Action("Import", networkActions.onImport)
            }
        }
        Line("Version") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Label(state.version, 14.sp, ink.soft, tabular = true)
                when (val update = state.update) {
                    UpdateState.Checking -> Label("Checking", 14.sp, ink.soft)
                    UpdateState.Latest -> Label("Up to date", 14.sp, ink.soft)
                    is UpdateState.Available -> Label("Update available", 14.sp, ink.ink)
                    is UpdateState.Downloading -> Label("Downloading ${update.percent}%", 14.sp, ink.soft, tabular = true)
                    UpdateState.Installing -> Label("Installing", 14.sp, ink.soft)
                    is UpdateState.Failed -> Label(update.message, 14.sp, NodeWeather.Cut.ink(ink.dark))
                    UpdateState.Off, UpdateState.Idle -> Unit
                }
                when (state.update) {
                    UpdateState.Idle, UpdateState.Latest -> Action("Check for updates", actions.onCheck)
                    is UpdateState.Failed -> Action("Retry", actions.onCheck)
                    is UpdateState.Available -> Action("Update", actions.onUpdate)
                    else -> Unit
                }
            }
        }
        // Open-Meteo's terms: the weather the cities show is credited to it.
        if (state.backdrop == Backdrop.City) {
            Label("Weather data: Open-Meteo.com", 12.sp, ink.soft, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun Line(title: String, content: @Composable RowScope.() -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().height(60.dp).hairline(ink.hair, bottom = true), verticalAlignment = Alignment.CenterVertically) {
        Label(title, 15.sp)
        Spacer(Modifier.weight(1f))
        content()
    }
}

/** One of a few, the chosen one underlined in ink. */
@Composable
private fun <T> Choices(choices: List<T>, chosen: T, title: (T) -> String, onPick: (T) -> Unit) {
    val ink = LocalInk.current
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        choices.forEach { choice ->
            val on = choice == chosen
            Box(
                Modifier.height(34.dp).press(bleed = 8.dp) { onPick(choice) }
                    .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(title(choice), 14.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * A DNS server: the presets, and Custom, which opens a field for an address of the person's own;
 * Enter keeps it when it is one, Esc closes it.
 */
@Composable
private fun DnsLine(title: String, presets: List<Pair<String, String>>, chosen: String, hint: String, onChoose: (String) -> Boolean) {
    val ink = LocalInk.current
    val custom = presets.none { it.first == chosen }
    var editing by remember(chosen) { mutableStateOf(false) }
    Column {
        Line(title) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                presets.forEach { (value, name) ->
                    Choice(name, !editing && value == chosen) { editing = false; onChoose(value) }
                }
                Choice("Custom", editing || custom) { editing = true }
            }
        }
        if (editing || custom) {
            var text by remember(chosen) { mutableStateOf(if (custom) chosen else "") }
            var wrong by remember(chosen) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    text,
                    { text = it; wrong = false },
                    Modifier.weight(1f).height(36.dp)
                        .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { editing = false; true } else false }
                        .hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.hair, bottom = true, width = 1.5.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.mono, fontSize = 13.sp),
                    cursorBrush = SolidColor(ink.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (onChoose(text)) editing = false else wrong = true }),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) Label(hint, 13.sp, ink.soft)
                            field()
                        }
                    },
                )
            }
        }
    }
}

/**
 * A list of pairs typed as one line each: each as a pill (× takes it out), and Add, which opens a
 * field. Hosts ("name address", or a hosts file's line) answer before any DNS server; DNS for
 * domains ("domain server") sends the names under a domain to a server of the person's own, as
 * an intranet's. Both come after the Claude block.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> PairsLine(title: String, items: List<T>, shown: (T) -> String, onRemove: (T) -> Unit, onAdd: (String) -> Boolean, hint: String) {
    val ink = LocalInk.current
    var adding by remember { mutableStateOf(false) }
    Column {
        Line(title) {
            if (items.isEmpty() && !adding) Label("None", 14.sp, ink.soft)
            Spacer(Modifier.width(18.dp))
            Box(Modifier.height(34.dp).press(bleed = 8.dp) { adding = !adding }, contentAlignment = Alignment.Center) {
                Label(if (adding) "Done" else "Add", 14.sp, weight = FontWeight.SemiBold)
            }
        }
        if (items.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item ->
                    Row(
                        Modifier.height(30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp)).background(ink.press)
                            .press { onRemove(item) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Label(shown(item), 13.sp, tabular = true)
                        Label("×", 14.sp, ink.soft)
                    }
                }
            }
        }
        if (adding) {
            var text by remember { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            BasicTextField(
                text,
                { text = it; wrong = false },
                Modifier.fillMaxWidth().padding(bottom = 10.dp).height(36.dp)
                    .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { adding = false; true } else false }
                    .hairline(if (wrong) NodeWeather.Cut.ink(ink.dark) else ink.hair, bottom = true, width = 1.5.dp),
                singleLine = true,
                textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.mono, fontSize = 13.sp),
                cursorBrush = SolidColor(ink.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (onAdd(text)) text = "" else wrong = true }),
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Label(hint, 13.sp, ink.soft)
                        field()
                    }
                },
            )
        }
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

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onClick).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Label(text, 14.sp, weight = FontWeight.SemiBold)
    }
}

/** A switch drawn in ink: filled with the knob to the right when on, a hairline ring when off. */
@Composable
internal fun Switch(on: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    val track = RoundedCornerShape(11.dp)
    Box(
        Modifier.size(40.dp, 22.dp).clip(track).press(onClick = onClick)
            .then(if (on) Modifier.background(ink.ink, track) else Modifier.border(1.5.dp, ink.soft, track))
            .padding(horizontal = 4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).background(if (on) ink.paper else ink.soft, CircleShape))
    }
}
