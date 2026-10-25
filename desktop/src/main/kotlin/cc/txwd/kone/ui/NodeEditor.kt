package cc.txwd.kone.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.txwd.kone.node.NodeDraft
import cc.txwd.kone.node.NodeForm

private val FINGERPRINTS = listOf("" to "None", "chrome" to "Chrome", "firefox" to "Firefox", "safari" to "Safari", "edge" to "Edge", "random" to "Random")
private val FLOWS = listOf("" to "None", "xtls-rprx-vision" to "Vision")
private val PLUGINS = listOf("" to "None", "obfs-local" to "obfs", "v2ray-plugin" to "v2ray-plugin")
private val CONGESTIONS = listOf("" to "Default", "bbr" to "BBR", "cubic" to "CUBIC", "new_reno" to "NewReno")
private val RELAYS = listOf("" to "Default", "native" to "Native", "quic" to "QUIC")

/**
 * A node written by hand: its kind, then only the fields that kind uses, grouped as the
 * connection is made (where, who, how it is encrypted, how it travels). [error] is what was
 * wrong the last time it was saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeEditor(start: NodeDraft, editing: Boolean, error: String?, saving: Boolean, onSave: (NodeDraft) -> Unit, onCancel: () -> Unit) {
    val ink = LocalInk.current
    var draft by remember(start) { mutableStateOf(start) }
    val fields = NodeForm.fields(draft.type)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).hairline(ink.hair, bottom = true).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(if (editing) "Edit node" else "Add node", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        if (!editing) {
            Field("Kind") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NodeForm.TYPES.forEach { (type, title) -> Pick(title, draft.type == type) { draft = draft.copy(type = type, security = NodeForm.securities(type).firstOrNull() ?: "tls") } }
                }
            }
        }
        Field("Name") { Text(draft.name, { draft = draft.copy(name = it) }, "Shown in KOne") }
        Field("Server") {
            Text(draft.server, { draft = draft.copy(server = it) }, "203.0.113.1 or node.example.com", Modifier.weight(1f))
            Spacer(Modifier.width(18.dp))
            Label("Port", 13.sp, ink.soft)
            Spacer(Modifier.width(10.dp))
            Text(draft.port, { draft = draft.copy(port = it.filter(Char::isDigit).take(5)) }, "443", Modifier.width(70.dp))
        }
        if ("uuid" in fields) Field("UUID") { Text(draft.uuid, { draft = draft.copy(uuid = it) }, "00000000-0000-0000-0000-000000000000", mono = true) }
        if ("username" in fields) Field("User") { Text(draft.username, { draft = draft.copy(username = it) }, "Optional") }
        if ("password" in fields) Field("Password") { Text(draft.password, { draft = draft.copy(password = it) }, if (draft.type == "socks" || draft.type == "http") "Optional" else "", secret = true) }
        if (draft.type == "shadowsocks") {
            Field("Cipher") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NodeForm.CIPHERS.forEach { cipher -> Pick(cipher, draft.method == cipher) { draft = draft.copy(method = cipher) } }
                }
            }
            Field("Plugin") { PLUGINS.forEach { (value, title) -> Pick(title, draft.plugin == value) { draft = draft.copy(plugin = value) } } }
            if (draft.plugin.isNotEmpty()) Field("Options") { Text(draft.pluginOptions, { draft = draft.copy(pluginOptions = it) }, "obfs=http;obfs-host=example.com", mono = true) }
        }
        if (draft.type == "vmess") {
            Field("Encryption") {
                Text(draft.method, { draft = draft.copy(method = it) }, "auto", Modifier.width(160.dp))
                Spacer(Modifier.width(18.dp))
                Label("Alter ID", 13.sp, ink.soft)
                Spacer(Modifier.width(10.dp))
                Text(draft.alterId, { draft = draft.copy(alterId = it.filter(Char::isDigit).take(5)) }, "0", Modifier.width(70.dp))
            }
        }
        if ("flow" in fields) Field("Flow") { FLOWS.forEach { (value, title) -> Pick(title, draft.flow == value) { draft = draft.copy(flow = value) } } }
        val securities = NodeForm.securities(draft.type)
        if (securities.isNotEmpty()) {
            Field("Encryption") { securities.forEach { value -> Pick(if (value == "none") "None" else value.uppercase().replace("REALITY", "Reality"), draft.security == value) { draft = draft.copy(security = value) } } }
        }
        val encrypted = "tls" in fields || (securities.isNotEmpty() && draft.security != "none")
        if (encrypted) {
            Field("SNI") { Text(draft.sni, { draft = draft.copy(sni = it) }, "The server's name, if not the server") }
            Field("ALPN") {
                Text(draft.alpn, { draft = draft.copy(alpn = it) }, "h2,http/1.1", Modifier.weight(1f))
                Spacer(Modifier.width(18.dp))
                Label("Allow insecure", 13.sp, ink.soft)
                Spacer(Modifier.width(10.dp))
                Switch(draft.insecure) { draft = draft.copy(insecure = !draft.insecure) }
            }
            if (draft.type != "hysteria2" && draft.type != "tuic") {
                Field("Fingerprint") { FINGERPRINTS.forEach { (value, title) -> Pick(title, draft.fingerprint == value) { draft = draft.copy(fingerprint = value) } } }
            }
            if (draft.security == "reality" && "security" in fields) {
                Field("Public key") { Text(draft.publicKey, { draft = draft.copy(publicKey = it) }, "", mono = true) }
                Field("Short ID") { Text(draft.shortId, { draft = draft.copy(shortId = it) }, "Optional", Modifier.width(200.dp), mono = true) }
            }
        }
        if ("transport" in fields) {
            Field("Transport") { NodeForm.TRANSPORTS.forEach { value -> Pick(if (value == "httpupgrade") "HTTPUpgrade" else value.uppercase().replace("GRPC", "gRPC"), draft.transport == value) { draft = draft.copy(transport = value) } } }
            when (draft.transport) {
                "ws", "http", "httpupgrade" -> Field("Host") {
                    Text(draft.host, { draft = draft.copy(host = it) }, "Optional", Modifier.weight(1f))
                    Spacer(Modifier.width(18.dp))
                    Label("Path", 13.sp, ink.soft)
                    Spacer(Modifier.width(10.dp))
                    Text(draft.path, { draft = draft.copy(path = it) }, "/", Modifier.weight(1f), mono = true)
                }
                "grpc" -> Field("Service") { Text(draft.serviceName, { draft = draft.copy(serviceName = it) }, "Service name", mono = true) }
            }
        }
        if (draft.type == "hysteria2") {
            Field("Hop ports") { Text(draft.hops, { draft = draft.copy(hops = it) }, "Optional, as 20000-30000", mono = true) }
            Field("Obfuscation") { Text(draft.obfsPassword, { draft = draft.copy(obfsPassword = it) }, "Salamander password, optional", secret = true) }
            Field("Speed") {
                Text(draft.up, { draft = draft.copy(up = it.filter(Char::isDigit).take(6)) }, "Up Mbps", Modifier.width(110.dp))
                Spacer(Modifier.width(18.dp))
                Text(draft.down, { draft = draft.copy(down = it.filter(Char::isDigit).take(6)) }, "Down Mbps", Modifier.width(110.dp))
            }
        }
        if (draft.type == "tuic") {
            Field("Congestion") { CONGESTIONS.forEach { (value, title) -> Pick(title, draft.congestion == value) { draft = draft.copy(congestion = value) } } }
            Field("UDP relay") { RELAYS.forEach { (value, title) -> Pick(title, draft.udpRelay == value) { draft = draft.copy(udpRelay = value) } } }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Spacer(Modifier.width(110.dp))
            Primary("Save", { onSave(draft) }, busy = if (saving) "Checking" else null)
            Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onCancel), contentAlignment = Alignment.Center) { Label("Cancel", 14.sp, ink.soft) }
            error?.let { Label(it, 13.sp, NodeWeather.Cut.ink(ink.dark)) }
        }
    }
}

@Composable
private fun Field(title: String, content: @Composable RowScope.() -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(110.dp)) { Label(title, 13.sp, ink.soft) }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
private fun RowScope.Text(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier.weight(1f), mono: Boolean = false, secret: Boolean = false) {
    val ink = LocalInk.current
    BasicTextField(
        value, onChange,
        modifier.height(32.dp).hairline(ink.hair, bottom = true, width = 1.5.dp),
        singleLine = true,
        textStyle = TextStyle(color = ink.ink, fontFamily = if (mono) Fonts.mono else Fonts.ui, fontSize = 13.sp),
        cursorBrush = SolidColor(ink.ink),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Label(hint, 13.sp, ink.soft)
                field()
            }
        },
    )
}

@Composable
private fun Pick(name: String, on: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.height(30.dp).press(bleed = 6.dp, onClick = onClick)
            .drawBehind { if (on) drawRect(ink.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(name, 13.sp, if (on) ink.ink else ink.soft, if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}
