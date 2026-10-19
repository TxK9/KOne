package io.nekohasekai.sfa.selfuse

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.VisualTransformation
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import io.nekohasekai.sfa.compose.screen.dashboard.Countries
import io.nekohasekai.sfa.compose.util.FlagIcons
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A node from a link: the profile that holds it, and whether this import created it. */
class ImportedNode(val profile: Profile, val isNew: Boolean)

/**
 * Builds and stores a paired profile from a share link (see [ShareLinks]). The link's name
 * becomes the node name, so category recognition works right away; without one the server
 * names it. A name that points to exactly one country also gives the node that flag. A node
 * that is already here is returned instead of a copy. Throws [ShareLinkException] for a link
 * that cannot be imported.
 */
suspend fun createPairedProfile(context: Context, link: String): ImportedNode = createPairedProfile(context, ShareLinks.parse(link))

/** [createPairedProfile] for a node already read, from a link or a Clash profile. */
suspend fun createPairedProfile(context: Context, shared: SharedNode): ImportedNode = withContext(Dispatchers.IO) {
    existingProfile(shared.outbound)?.let { return@withContext ImportedNode(it, isNew = false) }
    val content = PairedProfile.build(shared.outbound, HomeSettings.current(), InitialRuleSets.ensure(context))
    Libbox.checkConfig(content)
    val fileId = ProfileManager.nextFileID()
    val directory = File(context.filesDir, "configs").also { it.mkdirs() }
    val file = File(directory, "$fileId.json")
    try {
        file.writeText(content)
        file.setReadable(false, false)
        file.setReadable(true, true)
        val typed = TypedProfile().apply { type = TypedProfile.Type.Local; path = file.absolutePath }
        val name = shared.name ?: shared.outbound.optString("server").ifBlank { "Node" }
        val country = Countries.all.filter { it.matches(name) }.singleOrNull()
        val profile = Profile(name = name, typed = typed).apply {
            userOrder = ProfileManager.nextOrder()
            country?.let { icon = FlagIcons.idFor(it.code) }
        }
        ImportedNode(ProfileManager.create(profile, andSelect = false), isNew = true)
    } catch (failure: Exception) {
        file.delete()
        throw failure
    }
}

/** A local profile already carrying this node, read from its stored configuration. */
private suspend fun existingProfile(outbound: JSONObject): Profile? {
    val key = PairedProfile.nodeKey(outbound) ?: return null
    return ProfileManager.list().firstOrNull { profile ->
        profile.typed.type == TypedProfile.Type.Local && runCatching {
            val outbounds = JSONObject(File(profile.typed.path).readText()).optJSONArray("outbounds")
            outbounds != null && (0 until outbounds.length()).any { PairedProfile.nodeKey(outbounds.optJSONObject(it)) == key }
        }.getOrDefault(false)
    }
}

@Composable
fun PairedProfileDialog(onDismiss: () -> Unit, onCreated: (Profile) -> Unit, initialLink: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember(initialLink) { mutableStateOf(initialLink) }
    var revealLink by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import nodes") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Supports VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, and AnyTLS links.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; error = null },
                    label = { Text("Node link") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    visualTransformation = if (revealLink) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealLink = !revealLink }) {
                            Icon(if (revealLink) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (revealLink) R.string.knine_conceal_link else R.string.knine_reveal_link))
                        }
                    },
                    singleLine = true,
                    enabled = !busy,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.knine_import_steps), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && link.isNotBlank(),
                onClick = {
                    busy = true
                    val input = link
                    scope.launch {
                        try {
                            val created = createPairedProfile(context, input).profile
                            link = ""
                            onCreated(created)
                            Toast.makeText(context, "Node imported and selected. Tap Connect if disconnected, then choose Rule or Global.", Toast.LENGTH_LONG).show()
                            onDismiss()
                        } catch (failure: ShareLinkException) {
                            error = failure.message
                        } catch (_: Exception) {
                            error = "Could not save the profile. Check the link format and try again."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Importing" else "Import and select") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
