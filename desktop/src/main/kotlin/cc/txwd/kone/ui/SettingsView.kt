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

/** Appearance: as Windows is set, or always light, or always dark. */
enum class Appearance(val title: String) { System("System"), Light("Light"), Dark("Dark") }

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
    /** Whether KOne starts with Windows; null where it cannot (not installed), and the row is left out. */
    val autostart: Boolean?,
    val version: String,
    val update: UpdateState,
)

class SettingsActions(
    val onAppearance: (Appearance) -> Unit = {},
    val onAutostart: (Boolean) -> Unit = {},
    val onCheck: () -> Unit = {},
    val onUpdate: () -> Unit = {},
)

/** Settings: Appearance, Start with Windows, and the version with its update, one line each on the paper. */
@Composable
fun SettingsView(state: SettingsState, actions: SettingsActions) {
    val ink = LocalInk.current
    Column(Modifier.padding(horizontal = 40.dp, vertical = 26.dp).widthIn(max = 640.dp).fillMaxWidth()) {
        Line("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Appearance.entries.forEach { choice ->
                    val on = choice == state.appearance
                    Box(
                        Modifier.height(34.dp).press(bleed = 8.dp) { actions.onAppearance(choice) }
                            .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(choice.title, 14.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        state.autostart?.let { on ->
            Line("Start with Windows") { Switch(on) { actions.onAutostart(!on) } }
        }
        Line("Version") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Label(state.version, 14.sp, ink.soft, tabular = true)
                when (val update = state.update) {
                    UpdateState.Checking -> Label("Checking", 14.sp, ink.soft)
                    UpdateState.Latest -> Label("Up to date", 14.sp, ink.soft)
                    is UpdateState.Available -> Label("New version ${update.version}", 14.sp, ink.ink)
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

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onClick).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Label(text, 14.sp, weight = FontWeight.SemiBold)
    }
}

/** A switch drawn in ink: filled with the knob to the right when on, a hairline ring when off. */
@Composable
private fun Switch(on: Boolean, onClick: () -> Unit) {
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
