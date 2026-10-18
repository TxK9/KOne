package io.nekohasekai.sfa.compose.screen.dashboard

import android.net.Uri
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileUpload
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import androidx.compose.material3.CircularProgressIndicator
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.KnineGlassBottomSheet as ModalBottomSheet
import io.nekohasekai.sfa.compose.theme.BackdropClickableSurface as Surface
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.selfuse.PairedProfileDialog
import io.nekohasekai.sfa.selfuse.ImportedNode
import io.nekohasekai.sfa.selfuse.ShareLinkException
import io.nekohasekai.sfa.selfuse.ShareLinks
import io.nekohasekai.sfa.selfuse.createPairedProfile
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.component.qr.QRCodeDialog
import io.nekohasekai.sfa.compose.component.qr.QRSDialog
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.configuration.ProfileImportHandler
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.errorDialogBuilder
import io.nekohasekai.sfa.ktx.shareProfile
import io.nekohasekai.sfa.ktx.shareProfileAsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesCard(
    profiles: List<Profile>,
    selectedProfileId: Long,
    isLoading: Boolean,
    showAddProfileSheet: Boolean,
    showProfilePickerSheet: Boolean,
    updatingProfileId: Long? = null,
    updatedProfileId: Long? = null,
    serviceStatus: io.nekohasekai.sfa.constant.Status = io.nekohasekai.sfa.constant.Status.Stopped,
    onConnectRequested: () -> Unit = {},
    exitIp: String? = null,
    onProfileSelected: (Long) -> Unit,
    onProfileEdit: (Profile) -> Unit,
    onProfileDelete: (Profile) -> Unit,
    onProfileShare: (Profile) -> Unit,
    onProfileShareURL: (Profile) -> Unit,
    onProfileUpdate: (Profile) -> Unit,
    onProfileMove: (Int, Int) -> Unit,
    onShowAddProfileSheet: () -> Unit,
    onHideAddProfileSheet: () -> Unit,
    onShowProfilePickerSheet: () -> Unit,
    onHideProfilePickerSheet: () -> Unit,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
    panel: (@Composable (KnineImportActions) -> Unit)? = null,
) {
    var showPairedProfileDialog by remember { mutableStateOf(false) }
    var scannedNodeLink by remember { mutableStateOf("") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val importHandler = remember { ProfileImportHandler(context) }

    var showQRCodeDialog by remember { mutableStateOf(false) }
    var qrCodeProfile by remember { mutableStateOf<Profile?>(null) }

    var showQRSDialog by remember { mutableStateOf(false) }
    var qrsProfile by remember { mutableStateOf<Profile?>(null) }
    var qrsProfileData by remember { mutableStateOf<ByteArray?>(null) }

    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingQrsData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    var showQRScanSheet by remember { mutableStateOf(false) }

    val importFromFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                coroutineScope.launch {
                    when (val parseResult = importHandler.parseUri(uri)) {
                        is ProfileImportHandler.UriParseResult.Success -> {
                            withContext(Dispatchers.Main) {
                                pendingImportName = parseResult.name
                                pendingImportUri = uri
                                showImportConfirmDialog = true
                            }
                        }
                        is ProfileImportHandler.UriParseResult.Error -> {
                            withContext(Dispatchers.Main) {
                                context.errorDialogBuilder(Exception(parseResult.message)).show()
                            }
                        }
                    }
                }
            }
        }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            val selectedProfile = profiles.find { it.id == selectedProfileId }
            if (selectedProfile != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val profileData = createProfileContent(selectedProfile)
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(profileData)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.success_profile_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "${context.getString(R.string.failed_save_profile)}: ${e.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    val saveJsonFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val selectedProfile = profiles.find { it.id == selectedProfileId }
            if (selectedProfile != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val jsonContent = File(selectedProfile.typed.path).readText()
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(jsonContent.toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.success_profile_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "${context.getString(R.string.failed_save_profile)}: ${e.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    // Node links, from the clipboard or a QR code, are imported at once and the first is
    // selected. A node that is already here is selected instead of being added again.
    fun importNodeLinks(links: List<String>) {
        coroutineScope.launch {
            val imported = mutableListOf<ImportedNode>()
            var refusal: String? = null
            links.forEach { link ->
                try {
                    imported += createPairedProfile(context, link)
                } catch (failure: ShareLinkException) {
                    refusal = failure.message
                } catch (_: Exception) {
                    refusal = refusal ?: "Could not import this link. Check it and try again."
                }
            }
            imported.firstOrNull()?.let { onProfileSelected(it.profile.id) }
            val added = imported.count { it.isNew }
            val kept = imported.size - added
            val failed = links.size - imported.size
            val message = when {
                imported.isEmpty() -> refusal ?: "Could not import this link. Check it and try again."
                links.size == 1 && added == 1 -> "Imported and selected ${imported[0].profile.name}"
                links.size == 1 -> "${imported[0].profile.name} already exists and is selected."
                else -> buildString {
                    append("Imported $added nodes")
                    if (kept > 0) append(", $kept already existed")
                    if (failed > 0) append(", $failed could not be imported")
                }
            }
            Toast.makeText(context, message, if (failed > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    // The link is always copied from somewhere, so read it straight from the clipboard:
    // a node link imports at once, a sing-box remote profile link opens as a new profile.
    fun importFromClipboard() {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val links = ShareLinks.findAll(text)
        val remote = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("sing-box://import-remote-profile", ignoreCase = true) }
        when {
            links.isNotEmpty() -> importNodeLinks(links)
            remote != null -> coroutineScope.launch {
                when (val parsed = importHandler.parseQRCode(remote)) {
                    is ProfileImportHandler.QRCodeParseResult.RemoteProfile ->
                        onOpenNewProfile(NewProfileArgs(importName = parsed.name, importUrl = parsed.url))
                    else -> Toast.makeText(context, "The clipboard contains an unrecognized remote configuration link.", Toast.LENGTH_SHORT).show()
                }
            }
            else -> Toast.makeText(context, "No recognized links in the clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    val selectedProfile = profiles.find { it.id == selectedProfileId }

    if (panel != null) {
        // The photo dashboard draws its own panel and reuses this card's sheets and dialogs;
        // its Import offers the same ways in as the sheet below.
        panel(
            KnineImportActions(
                clipboard = ::importFromClipboard,
                scan = { showQRScanSheet = true },
                file = { importFromFileLauncher.launch("*/*") },
                manual = { onOpenNewProfile(NewProfileArgs()) },
            ),
        )
    } else {
    Column(Modifier.fillMaxWidth()) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.knine_current_profile),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.no_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(
                    onClick = onShowAddProfileSheet,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_profile), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileSelectorButton(
                        selectedProfile = selectedProfile,
                        exitIp = exitIp,
                        onClick = onShowProfilePickerSheet,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        onClick = onShowAddProfileSheet,
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_profile),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (selectedProfile?.typed?.type == TypedProfile.Type.Remote) {
                    Spacer(modifier = Modifier.height(6.dp))
                    BackdropContrastScope {
                        ProfileInfoRow(profile = selectedProfile)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                ProfileActionRow(
                    profile = selectedProfile,
                    isUpdating = selectedProfile?.id == updatingProfileId,
                    showUpdateSuccess = selectedProfile?.id == updatedProfileId,
                    onEdit = { selectedProfile?.let { onProfileEdit(it) } },
                    onUpdate = { selectedProfile?.let { onProfileUpdate(it) } },
                    onShareFile = {
                        selectedProfile?.let {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    context.shareProfile(it)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        }
                    },
                    onSaveFile = {
                        selectedProfile?.let {
                            saveFileLauncher.launch("${it.name}.bpf")
                        }
                    },
                    onSaveJson = {
                        selectedProfile?.let {
                            saveJsonFileLauncher.launch("${it.name}.json")
                        }
                    },
                    onShareJson = {
                        selectedProfile?.let {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    context.shareProfileAsJson(it)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        }
                    },
                    onShareURL = {
                        selectedProfile?.let {
                            qrCodeProfile = it
                            showQRCodeDialog = true
                        }
                    },
                    onShareQRS = {
                        selectedProfile?.let { profile ->
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val data = createProfileContent(profile)
                                    withContext(Dispatchers.Main) {
                                        qrsProfile = profile
                                        qrsProfileData = data
                                        showQRSDialog = true
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    CountryProfileDeck(
        profiles = profiles,
        selectedProfileId = selectedProfileId,
        onProfileSelected = onProfileSelected,
        isLoading = isLoading,
        serviceStatus = serviceStatus,
        onConnectRequested = onConnectRequested,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    }
    }

    if (showProfilePickerSheet) {
        ProfilePickerSheet(
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            onProfileSelected = { profile -> onProfileSelected(profile.id) },
            onProfileEdit = onProfileEdit,
            onProfileDelete = onProfileDelete,
            onProfileMove = onProfileMove,
            onDismiss = onHideProfilePickerSheet,
        )
    }

    if (showPairedProfileDialog) {
        PairedProfileDialog(
            initialLink = scannedNodeLink,
            onDismiss = { showPairedProfileDialog = false; scannedNodeLink = "" },
            onCreated = { onProfileSelected(it.id) },
        )
    }

    if (showAddProfileSheet && panel == null) {
        ModalBottomSheet(
            onDismissRequest = onHideAddProfileSheet,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_profile),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        importFromClipboard()
                    },
                    leadingContent = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    headlineContent = { Text("Import from clipboard") },
                    supportingContent = { Text("Read copied node links") },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        importFromFileLauncher.launch("*/*")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.FileUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_import_file))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.import_from_file_description))
                    },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        showQRScanSheet = true
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_scan_qr_code))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.scan_qr_code_description))
                    },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        onOpenNewProfile(NewProfileArgs())
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_create_manually))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.create_new_profile_description))
                    },
                )
            }
        }
    }

    if (showQRCodeDialog && qrCodeProfile != null) {
        val profile = qrCodeProfile!!
        val link = remember(profile) {
            Libbox.generateRemoteProfileImportLink(
                profile.name,
                profile.typed.remoteURL,
            )
        }
        QRCodeDialog(
            content = link,
            onDismiss = {
                showQRCodeDialog = false
                qrCodeProfile = null
            },
        )
    }

    if (showQRSDialog && qrsProfile != null && qrsProfileData != null) {
        QRSDialog(
            profileData = qrsProfileData!!,
            profileName = qrsProfile!!.name,
            onDismiss = {
                showQRSDialog = false
                qrsProfile = null
                qrsProfileData = null
            },
        )
    }

    if (showImportConfirmDialog && pendingImportName != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportName = null
                pendingQrsData = null
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.import_profile_confirm_title)) },
            text = { Text(stringResource(R.string.import_profile_confirm_message, pendingImportName!!)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        val qrsData = pendingQrsData
                        val importUri = pendingImportUri
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                        coroutineScope.launch {
                            if (qrsData != null) {
                                when (val result = importHandler.importFromQRSData(qrsData)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onProfileEdit(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            } else if (importUri != null) {
                                when (val result = importHandler.importFromUri(importUri)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onProfileEdit(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQRScanSheet) {
        QRScanSheet(
            onDismiss = { showQRScanSheet = false },
            onScanResult = { result ->
                showQRScanSheet = false
                when (result) {
                    // A KOne key belongs on the start page, not among the nodes.
                    is QRScanResult.ClaimLink -> Toast.makeText(context, "This is a KOne access key, not a node.", Toast.LENGTH_SHORT).show()
                    is QRScanResult.NodeLink -> {
                        // The dashboard panel imports a scanned node like a copied one; the
                        // plain card shows the link in its dialog first.
                        if (panel != null) {
                            importNodeLinks(listOf(result.link))
                        } else {
                            scannedNodeLink = result.link
                            showPairedProfileDialog = true
                        }
                    }
                    is QRScanResult.QRSData -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRSData(result.data)) {
                                is ProfileImportHandler.QRSParseResult.Success -> {
                                    withContext(Dispatchers.Main) {
                                        pendingImportName = parseResult.name
                                        pendingQrsData = result.data
                                        showImportConfirmDialog = true
                                    }
                                }
                                is ProfileImportHandler.QRSParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                    is QRScanResult.RemoteProfile -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRCode(result.uri.toString())) {
                                is ProfileImportHandler.QRCodeParseResult.RemoteProfile -> {
                                    withContext(Dispatchers.Main) {
                                        onOpenNewProfile(
                                            NewProfileArgs(
                                                importName = parseResult.name,
                                                importUrl = parseResult.url,
                                            ),
                                        )
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.LocalProfile -> {
                                    when (val importResult = importHandler.importFromQRCode(result.uri.toString())) {
                                        is ProfileImportHandler.ImportResult.Success -> {
                                            withContext(Dispatchers.Main) {
                                                onProfileEdit(importResult.profile)
                                            }
                                        }
                                        is ProfileImportHandler.ImportResult.Error -> {
                                            withContext(Dispatchers.Main) {
                                                context.errorDialogBuilder(Exception(importResult.message)).show()
                                            }
                                        }
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

private suspend fun createProfileContent(profile: Profile): ByteArray {
    val content = ProfileContent()
    content.name = profile.name
    when (profile.typed.type) {
        TypedProfile.Type.Local -> {
            content.type = Libbox.ProfileTypeLocal
        }
        TypedProfile.Type.Remote -> {
            content.type = Libbox.ProfileTypeRemote
        }
    }
    content.config = java.io.File(profile.typed.path).readText()
    content.remotePath = profile.typed.remoteURL
    content.autoUpdate = profile.typed.autoUpdate
    content.autoUpdateInterval = profile.typed.autoUpdateInterval
    content.lastUpdated = profile.typed.lastUpdated.time
    return content.encode()
}

@Composable
private fun ProfileInfoRow(profile: Profile?) {
    if (profile == null) return

    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (profile.typed.type == TypedProfile.Type.Remote) {
                    Icons.Default.Cloud
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (profile.typed.type == TypedProfile.Type.Remote) {
                    stringResource(R.string.profile_type_remote)
                } else {
                    stringResource(R.string.profile_type_local)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (profile.typed.type == TypedProfile.Type.Remote) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = RelativeTimeFormatter.format(context, profile.typed.lastUpdated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileActionRow(
    profile: Profile?,
    isUpdating: Boolean,
    showUpdateSuccess: Boolean,
    onEdit: () -> Unit,
    onUpdate: () -> Unit,
    onShareFile: () -> Unit,
    onSaveFile: () -> Unit,
    onSaveJson: () -> Unit,
    onShareJson: () -> Unit,
    onShareURL: () -> Unit,
    onShareQRS: () -> Unit,
) {
    if (profile == null) return

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionButton(
            icon = Icons.Default.Edit,
            contentDescription = stringResource(R.string.edit),
            onClick = onEdit,
        )

        if (profile.typed.type == TypedProfile.Type.Remote) {
            ActionButton(
                icon = when {
                    showUpdateSuccess -> Icons.Default.Check
                    else -> Icons.Default.Refresh
                },
                contentDescription = stringResource(R.string.update_profile),
                onClick = onUpdate,
                enabled = !isUpdating && !showUpdateSuccess,
                isLoading = isUpdating,
            )
        }

        ShareButton(
            profile = profile,
            onShareFile = onShareFile,
            onSaveFile = onSaveFile,
            onSaveJson = onSaveJson,
            onShareJson = onShareJson,
            onShareURL = onShareURL,
            onShareQRS = onShareQRS,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (isSystemInDarkTheme()) {
            lerp(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.surfaceContainerHigh,
                0.5f,
            )
        } else {
            MaterialTheme.colorScheme.surfaceDim
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
            Text(
                if (contentDescription == stringResource(R.string.update_profile)) stringResource(R.string.update) else contentDescription,
                modifier = Modifier.semantics { this.contentDescription = contentDescription },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ShareButton(
    profile: Profile,
    onShareFile: () -> Unit,
    onSaveFile: () -> Unit,
    onSaveJson: () -> Unit,
    onShareJson: () -> Unit,
    onShareURL: () -> Unit,
    onShareQRS: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ActionButton(
            icon = Icons.Default.IosShare,
            contentDescription = stringResource(R.string.menu_share),
            onClick = { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_as_file)) },
                onClick = {
                    expanded = false
                    onSaveFile()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_file)) },
                onClick = {
                    expanded = false
                    onShareFile()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_content_json)) },
                onClick = {
                    expanded = false
                    onSaveJson()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DataObject,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_content_json)) },
                onClick = {
                    expanded = false
                    onShareJson()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DataObject,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            if (profile.typed.type == TypedProfile.Type.Remote) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_share_url)) },
                    onClick = {
                        expanded = false
                        onShareURL()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_qrs)) },
                onClick = {
                    expanded = false
                    onShareQRS()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}
