package io.nekohasekai.sfa.compose.screen.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import io.nekohasekai.sfa.compose.theme.knineSurface
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import androidx.compose.foundation.background
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import io.nekohasekai.sfa.compose.theme.KnineLoadingState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Update
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import io.nekohasekai.sfa.compose.theme.BackdropButton as Button
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.KnineSurface as Surface
import androidx.compose.material3.Switch
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.SelectableMessageDialog
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.FlagIcons
import io.nekohasekai.sfa.compose.util.ProfileIconGlyph
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.compose.util.icons.MaterialIconsLibrary
import io.nekohasekai.sfa.database.TypedProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    profileId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToIconSelection: (currentIconId: String?) -> Unit = {},
    onNavigateToEditContent: (isReadOnly: Boolean) -> Unit = {},
    viewModel: EditProfileViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Clear success indicator after delay
    LaunchedEffect(uiState.showUpdateSuccess) {
        if (uiState.showUpdateSuccess) {
            kotlinx.coroutines.delay(1500)
            viewModel.clearUpdateSuccess()
        }
    }

    // Dialog states
    var showErrorDialog by remember { mutableStateOf(false) }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }

    // Launch icon selection screen when needed
    if (uiState.showIconDialog) {
        LaunchedEffect(Unit) {
            viewModel.hideIconDialog()
            onNavigateToIconSelection(uiState.icon)
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

    // Unsaved changes dialog
    if (showUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedChangesDialog = false
                        onNavigateBack()
                    },
                ) {
                    Text(
                        stringResource(R.string.discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnsavedChangesDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // Handle back navigation
    val handleBack = {
        if (uiState.hasChanges) {
            showUnsavedChangesDialog = true
        } else {
            onNavigateBack()
        }
    }

    // Intercept system back button
    BackHandler(enabled = uiState.hasChanges) {
        showUnsavedChangesDialog = true
    }

    OverrideTopBar {
        TopAppBar(
            // The page shows the node itself up top; a title would only repeat it.
            title = {},
            navigationIcon = {
                IconButton(onClick = handleBack) {
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

    val bottomBarPadding =
        if (uiState.hasChanges) {
            88.dp
        } else {
            0.dp
        }

    Box(
        modifier = Modifier.fillMaxSize().imePadding(),
    ) {
        val scaffoldPadding = LocalScaffoldPadding.current

        // Progress indicator at top (only for initial loading)
        if (uiState.isLoading) {
            KnineLoadingState(stringResource(R.string.loading), Modifier.fillMaxSize().padding(scaffoldPadding))
        }

        if (!uiState.isLoading) {
            KnineNodeEditor(
                state = uiState,
                onName = viewModel::updateName,
                onIcon = viewModel::updateIcon,
                onMoreIcons = { onNavigateToIconSelection(uiState.icon) },
                onContent = { onNavigateToEditContent(uiState.profileType == TypedProfile.Type.Remote) },
                onRemoteUrl = viewModel::updateRemoteUrl,
                onAutoUpdate = viewModel::updateAutoUpdate,
                onAutoUpdateInterval = viewModel::updateAutoUpdateInterval,
                onLeaveOut = viewModel::updateLeaveOut,
                onUpdateNow = viewModel::updateRemoteProfile,
                onDeleted = onNavigateBack,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(scaffoldPadding)
                    .padding(bottom = bottomBarPadding)
                    .padding(horizontal = 16.dp),
            )
        }

        // A round save button rises from the bottom only once something has changed.
        AnimatedVisibility(
            visible = uiState.hasChanges,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SaveButton(
                saving = uiState.isSaving,
                enabled = !uiState.isSaving && uiState.autoUpdateIntervalError == null,
                onSave = viewModel::saveChanges,
                modifier = Modifier.padding(bottom = scaffoldPadding.calculateBottomPadding() + 18.dp),
            )
        }
    }
}

@Composable
private fun SaveButton(saving: Boolean, enabled: Boolean, onSave: () -> Unit, modifier: Modifier) {
    BackdropContrastScope(modifier) {
        val ink = androidx.compose.material3.LocalContentColor.current
        val surface = knineSurface()
        Row(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(ink)
                .clickable(enabled = enabled, onClick = onSave)
                .padding(horizontal = 30.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), color = surface, strokeWidth = 2.dp)
            } else {
                androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null, tint = surface, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(
                stringResource(R.string.save),
                color = surface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }
    }
}
