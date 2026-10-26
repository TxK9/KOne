package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.compose.theme.KnineMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.nekohasekai.sfa.R
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.connection.*
import io.nekohasekai.sfa.udp.UdpRuntime
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import kotlinx.coroutines.*

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

@Composable
internal fun CallingOptionsEditor(options: CallingOptions, saving: Boolean, message: String, enabled: Boolean = true, onChange: (CallingOptions) -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    val editable = !saving && enabled
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=TestGutter,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.kone_video_compatibility),style=MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.kone_video_compatibility_detail),style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.graphicsLayer{alpha=0.7f})
            }
            KnineSwitch(checked=options.videoTcp,onCheckedChange={onChange(options.copy(videoTcp=it))},enabled=editable)
        }
        // One row, like a setting: the name, a word only while it cannot simply be switched, the switch.
        Row(Modifier.fillMaxWidth().padding(horizontal=TestGutter,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Call compatibility",style=MaterialTheme.typography.bodyLarge)
                val state = if(saving) "Applying" else if (!enabled) "Connect to a node to edit" else null
                if (state != null) Text(state,style=MaterialTheme.typography.bodySmall,modifier=Modifier.graphicsLayer{alpha=0.7f})
            }
            KnineSwitch(checked=options.enabled,onCheckedChange={onChange(options.copy(enabled=it))},enabled=editable)
        }
        if(message.isNotEmpty()) TestNote(message,Modifier.padding(bottom=8.dp))
        if (options.enabled) {
            TestFold("Advanced options",advanced,{advanced=!advanced},enabled=editable)
            AnimatedVisibility(advanced, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                Column(Modifier.fillMaxWidth()) {
                    ChoiceRow("DNS",options.dns.label,editable){editing="dns"}
                    ChoiceRow("MTU",if(options.mtu==0) "Use profile setting" else options.mtu.toString(),editable){editing="mtu"}
                }
            }
            TestNote("Direct rules come first, and only call traffic that enters KOne is changed. The node must carry UDP.",Modifier.padding(top=4.dp))
        }
    }
    editing?.let { field ->
        val choices=if(field=="dns") CallingDns.entries.map { it.name to it.label }
            else listOf(0 to "Use profile setting",1280 to "1280",1400 to "1400",1500 to "1500").map{it.first.toString() to it.second}
        val selected=if(field=="dns") options.dns.name else options.mtu.toString()
        KnineGlassAlertDialog(onDismissRequest={editing=null},title={Text(if(field=="dns") "DNS" else "MTU")},
            text={Column {
                choices.forEach { (value,label) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        editing=null
                        if (enabled && !saving) onChange(if(field=="dns") options.copy(dns=CallingDns.valueOf(value)) else options.copy(mtu=value.toInt()))
                    }.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=selected==value,onClick=null);Spacer(Modifier.width(10.dp));Text(label)
                    }
                }
            }},confirmButton={})
    }
}

/** A setting with its current value on the right, on the tests' gutter. */
@Composable
private fun ChoiceRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick).padding(horizontal=TestGutter,vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
        Text(value,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.graphicsLayer{alpha=0.75f})
    }
}
