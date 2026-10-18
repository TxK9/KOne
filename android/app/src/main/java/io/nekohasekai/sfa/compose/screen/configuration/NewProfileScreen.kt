package io.nekohasekai.sfa.compose.screen.configuration

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import io.nekohasekai.sfa.compose.theme.BackdropButton as Button
import androidx.compose.material3.ButtonDefaults
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropButton as OutlinedButton
import androidx.compose.material3.OutlinedCard
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.KnineSurface as Surface
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.SelectableMessageDialog
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProfileScreen(
    importName: String? = null,
    importUrl: String? = null,
    qrsData: ByteArray? = null,
    onNavigateBack: () -> Unit,
    onProfileCreated: (profileId: Long) -> Unit,
    viewModel: NewProfileViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(importName, importUrl, qrsData) {
        if (qrsData != null) {
            viewModel.initializeFromQRSImport(importName, qrsData)
        } else {
            viewModel.initializeFromQRImport(importName, importUrl)
        }
    }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                val fileName =
                    context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("_display_name")
                        cursor.moveToFirst()
                        cursor.getString(nameIndex)
                    }
                viewModel.setImportUri(it, fileName)
            }
        }

    // Error dialog state
    var showErrorDialog by remember { mutableStateOf(false) }

    // Handle success
    LaunchedEffect(uiState.isSuccess, uiState.createdProfile) {
        if (uiState.isSuccess) {
            uiState.createdProfile?.let { profile ->
                onProfileCreated(profile.id)
            }
        }
    }

    // Show error dialog when there's an error message
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            showErrorDialog = true
        }
    }

    // Error dialog
    if (showErrorDialog) {
        SelectableMessageDialog(
            title = stringResource(R.string.error_title),
            message = uiState.errorMessage ?: "",
            onDismiss = {
                showErrorDialog = false
                viewModel.clearError()
            },
        )
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_new_profile)) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
            colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(scaffoldPadding)
                .padding(bottom = 88.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Profile Name
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.basic_information),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = viewModel::updateName,
                        label = { Text(stringResource(R.string.profile_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.nameError != null,
                        supportingText = {
                            uiState.nameError?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
            }

            // Profile Type Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.profile_type),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileChoiceOption(
                            text = stringResource(R.string.profile_type_local),
                            selected = uiState.profileType == ProfileType.Local,
                            onClick = { viewModel.updateProfileType(ProfileType.Local) },
                            modifier = Modifier.weight(1f),
                        )
                        ProfileChoiceOption(
                            text = stringResource(R.string.profile_type_remote),
                            selected = uiState.profileType == ProfileType.Remote,
                            onClick = { viewModel.updateProfileType(ProfileType.Remote) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Local Profile Options
            AnimatedVisibility(
                visible = uiState.profileType == ProfileType.Local,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.profile_source),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProfileChoiceOption(
                                text = stringResource(R.string.profile_source_create_new),
                                selected = uiState.profileSource == ProfileSource.CreateNew,
                                onClick = { viewModel.updateProfileSource(ProfileSource.CreateNew) },
                                modifier = Modifier.weight(1f),
                            )
                            ProfileChoiceOption(
                                text = stringResource(R.string.profile_source_import),
                                selected = uiState.profileSource == ProfileSource.Import,
                                onClick = { viewModel.updateProfileSource(ProfileSource.Import) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        AnimatedVisibility(
                            visible = uiState.profileSource == ProfileSource.Import,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                OutlinedCard(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    modifier = Modifier.fillMaxWidth(),
                                    border =
                                    BorderStroke(
                                        1.dp,
                                        if (uiState.importError != null) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.FileUpload,
                                            contentDescription = null,
                                            tint =
                                            if (uiState.importError != null) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = uiState.importFileName ?: stringResource(R.string.profile_import_file),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (uiState.importFileName != null) {
                                                Text(
                                                    text = stringResource(R.string.group_selected_title),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                                uiState.importError?.let { error ->
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Remote Profile Options
            AnimatedVisibility(
                visible = uiState.profileType == ProfileType.Remote,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.remote_configuration),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        OutlinedTextField(
                            value = uiState.remoteUrl,
                            onValueChange = viewModel::updateRemoteUrl,
                            label = { Text(stringResource(R.string.profile_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = uiState.remoteUrlError != null,
                            supportingText = {
                                uiState.remoteUrlError?.let { error ->
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.profile_auto_update),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                            KnineSwitch(
                                checked = uiState.autoUpdate,
                                onCheckedChange = viewModel::updateAutoUpdate,
                            )
                        }

                        AnimatedVisibility(visible = uiState.autoUpdate) {
                            OutlinedTextField(
                                value = uiState.autoUpdateInterval.toString(),
                                onValueChange = viewModel::updateAutoUpdateInterval,
                                label = { Text(stringResource(R.string.profile_auto_update_interval)) },
                                supportingText = { Text(stringResource(R.string.profile_auto_update_interval_minimum_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier =
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    .padding(16.dp),
            ) {
                Button(
                    onClick = { viewModel.validateAndCreateProfile() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = !uiState.isSaving,
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_create))
                    }
                }
            }
        }
    }
}


@Composable
private fun ProfileChoiceOption(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp).semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        border = BorderStroke(if (selected) 1.25.dp else 0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.65f else 0.20f)),
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
