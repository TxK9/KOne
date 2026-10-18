package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
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

/** Settings: Appearance, Background (with Ambient sound for the cities), Start with Windows, Keyboard shortcut, and the version with its update, one line each on the paper. */
@Composable
fun SettingsView(state: SettingsState, actions: SettingsActions) {
    val ink = LocalInk.current
    Column(Modifier.padding(horizontal = 40.dp, vertical = 26.dp).widthIn(max = 640.dp).fillMaxWidth()) {
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
