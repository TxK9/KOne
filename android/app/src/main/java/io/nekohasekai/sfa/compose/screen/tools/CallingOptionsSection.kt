package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.connection.CallingDns
import io.nekohasekai.sfa.connection.CallingOptionUpdate
import io.nekohasekai.sfa.connection.CallingOptionUpdates
import io.nekohasekai.sfa.connection.CallingOptions
import io.nekohasekai.sfa.connection.CallingPreferences
import io.nekohasekai.sfa.connection.callingUpdateMessage
import io.nekohasekai.sfa.udp.UdpRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

@Composable
internal fun CallingOptionsSection(profileId: Long, editable: Boolean = true) {
    val context = LocalContext.current
    val store = remember(context) { CallingPreferences(context) }
    val updates by CallingOptionUpdates.state.collectAsState()
    val update = updates[profileId] ?: CallingOptionUpdate()
    val running by UdpRuntime.active.collectAsState()
    var options by remember(profileId) { mutableStateOf<CallingOptions?>(null) }
    LaunchedEffect(profileId, update.revision, update.busy) {
        if (!update.busy) options = withContext(Dispatchers.IO) { store.read(profileId) }
    }
    val current = options ?: return
    val message = callingUpdateMessage(update, profileId, running?.id, running?.epoch, editable)
    CallingOptionsEditor(current, update.busy, message, enabled = editable) { next ->
        if (editable && !update.busy && next != current) {
            CallingOptionUpdates.request(context, profileId, current, next)
        }
    }
}

private val MtuChoices = listOf(0, 1280, 1400, 1500)

private const val PROFILE_MTU = "Use profile setting"

private enum class CallingField { Dns, Mtu }

@Composable
internal fun CallingOptionsEditor(options: CallingOptions, saving: Boolean, message: String, enabled: Boolean = true, onChange: (CallingOptions) -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    // The one choice open under its row; choosing closes it again, in place rather than in a dialog.
    var editing by remember { mutableStateOf<CallingField?>(null) }
    val editable = !saving && enabled
    val opened = editing.takeIf { editable }
    Column(Modifier.fillMaxWidth()) {
        SwitchRow(
            title = stringResource(R.string.kone_video_compatibility),
            detail = stringResource(R.string.kone_video_compatibility_detail),
            checked = options.videoTcp,
            enabled = editable,
            onChange = { onChange(options.copy(videoTcp = it)) },
        )
        // One row, like a setting: the name, a word only while it cannot simply be switched, the switch.
        SwitchRow(
            title = "Call compatibility",
            detail = if (saving) "Applying" else if (!enabled) "Connect to a node to edit" else null,
            checked = options.enabled,
            enabled = editable,
            onChange = { onChange(options.copy(enabled = it)) },
        )
        if (message.isNotEmpty()) TestNote(message, Modifier.padding(bottom = 8.dp))
        if (options.enabled) {
            TestFold("Advanced options", advanced, { advanced = !advanced }, enabled = editable)
            AnimatedVisibility(advanced, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                Column(Modifier.fillMaxWidth()) {
                    ChoiceRow(
                        title = "DNS",
                        value = options.dns.label,
                        open = opened == CallingField.Dns,
                        enabled = editable,
                        choices = CallingDns.entries.map { it.label },
                        selected = options.dns.ordinal,
                        onToggle = { editing = if (opened == CallingField.Dns) null else CallingField.Dns },
                        onChoose = { index ->
                            editing = null
                            onChange(options.copy(dns = CallingDns.entries[index]))
                        },
                    )
                    ChoiceRow(
                        title = "MTU",
                        value = mtuLabel(options.mtu),
                        open = opened == CallingField.Mtu,
                        enabled = editable,
                        choices = MtuChoices.map(::mtuLabel),
                        selected = MtuChoices.indexOf(options.mtu),
                        onToggle = { editing = if (opened == CallingField.Mtu) null else CallingField.Mtu },
                        onChoose = { index ->
                            editing = null
                            onChange(options.copy(mtu = MtuChoices[index]))
                        },
                    )
                }
            }
            TestNote("Direct rules come first, and only call traffic that enters KOne is changed. The node must carry UDP.", Modifier.padding(top = 4.dp))
        }
    }
}

private fun mtuLabel(mtu: Int): String = if (mtu == 0) PROFILE_MTU else mtu.toString()

/** A setting and its switch on the tests' gutter; the line under the title appears only when there is one. */
@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TestGutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.graphicsLayer { alpha = 0.7f })
            }
        }
        Box(Modifier.padding(start = 12.dp)) {
            KnineSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

/**
 * A setting with its current value on the right, on the tests' gutter. Tapping it opens its
 * choices underneath, the current one ticked; neither the title nor the value is ever cut.
 */
@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    open: Boolean,
    enabled: Boolean,
    choices: List<String>,
    selected: Int,
    onToggle: () -> Unit,
    onChoose: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = stringResource(if (open) R.string.kone_row_hide_details else R.string.kone_row_show_details),
                    onClick = onToggle,
                )
                .padding(horizontal = TestGutter, vertical = 13.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleAndValue(
                title = { Text(title, style = MaterialTheme.typography.bodyLarge) },
                value = { Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.75f }) },
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(open, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                choices.forEachIndexed { index, label ->
                    val chosen = index == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton) { onChoose(index) }
                            .padding(start = TestGutter + 12.dp, end = TestGutter, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).graphicsLayer { alpha = if (chosen) 1f else 0.75f },
                        )
                        Box(Modifier.padding(start = 12.dp).size(18.dp), contentAlignment = Alignment.Center) {
                            if (chosen) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
