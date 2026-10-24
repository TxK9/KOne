package io.nekohasekai.sfa.selfuse

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
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        title = { Text("Import VLESS node") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Supports VLESS TCP nodes with TLS or Reality.", style = MaterialTheme.typography.bodyMedium)
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
                            val created = withContext(Dispatchers.IO) {
                                val content = PairedProfile.fromVlessLink(input, InitialRuleSets.ensure(context))
                                Libbox.checkConfig(content)
                                val fileId = ProfileManager.nextFileID()
                                val directory = File(context.filesDir, "configs").also { it.mkdirs() }
                                val file = File(directory, "$fileId.json")
                                try {
                                    file.writeText(content)
                                    file.setReadable(false, false)
                                    file.setReadable(true, true)
                                    val typed = TypedProfile().apply { type = TypedProfile.Type.Local; path = file.absolutePath }
                                    val profile = Profile(name = PairedProfile.displayName(input), typed = typed).apply { userOrder = ProfileManager.nextOrder() }
                                    ProfileManager.create(profile, andSelect = false)
                                } catch (failure: Exception) {
                                    file.delete()
                                    throw failure
                                }
                            }
                            link = ""
                            onCreated(created)
                            Toast.makeText(context, "Node imported and selected. Tap Connect if disconnected, then choose Rule or Global.", Toast.LENGTH_LONG).show()
                            onDismiss()
                        } catch (failure: IllegalArgumentException) {
                            error = failure.message ?: "Invalid connection parameters"
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
