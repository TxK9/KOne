package io.nekohasekai.sfa.update

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.tools.TestFold
import io.nekohasekai.sfa.compose.screen.tools.TestGutter
import io.nekohasekai.sfa.compose.screen.tools.TestNote
import io.nekohasekai.sfa.compose.screen.tools.TestRow
import io.nekohasekai.sfa.managed.ManagedState
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

@Composable
fun KoneUpdatePrompt() {
    if (!KoneUpdates.enabled) return
    val context = LocalContext.current
    val state by KoneUpdates.state.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // This permission belongs to updates. The VPN permission callback starts the
    // service and must never be used for an update notification request.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        KoneUpdates.onNotificationPermissionResult(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { KoneUpdates.onResume(context) }
    LaunchedEffect(lifecycleState, state.release?.versionCode, state.release?.sha256, state.phase) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            KoneUpdates.offerForeground(context)
            if (KoneUpdates.needsNotificationPermission(context)) {
                KoneUpdates.markNotificationPermissionRequested(context)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    fun permissionSettings() {
        KoneUpdates.dismissPermissionSetup(context)
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }.onFailure {
            android.widget.Toast.makeText(context, "Allow KOne to install updates in system settings.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val release = state.release ?: return
    if (state.showNonce == 0) return
    var dismissed by remember(state.showNonce) { mutableStateOf(false) }
    if (dismissed) return
    val busy = state.phase == KoneUpdatePhase.Downloading || state.phase == KoneUpdatePhase.Installing
    AlertDialog(
        onDismissRequest = {
            dismissed = true
            KoneUpdates.dismissPrompt(context)
        },
        title = { Text(if (state.important) "Important update" else "Update available") },
        text = { UpdateRows(release, updateLine(state, release)) },
        confirmButton = {
            // While the package downloads or installs, its row says so; there is nothing to press.
            if (!busy) TextButton(onClick = {
                if (state.verified && !state.permissionGranted) permissionSettings() else KoneUpdates.updateNow(context)
            }) {
                Text(when {
                    state.verified && !state.permissionGranted -> "Allow"
                    state.verified -> "Install"
                    else -> "Download"
                })
            }
        },
        dismissButton = {
            TextButton(onClick = {
                dismissed = true
                KoneUpdates.dismissPrompt(context)
            }) { Text("Later") }
        },
    )
}

/** The prompt's status as a row: a few words, a figure on the right, a weather when it failed, why a tap below. */
internal data class UpdateLine(val title: String, val value: String? = null, val weather: NodeWeather? = null, val detail: String? = null)

/** Kept short: a dialog leaves the row less room than a page, and a row's title never wraps. */
internal fun updateLine(state: KoneUpdateStatus, release: KoneRelease): UpdateLine = when (state.phase) {
    KoneUpdatePhase.Downloading -> UpdateLine("Downloading", "${(state.progress * 100).toInt()}%")
    KoneUpdatePhase.Installing -> UpdateLine("Installing")
    KoneUpdatePhase.Confirmation -> UpdateLine("Needs confirmation", detail = "Confirm installation in the system dialog.")
    KoneUpdatePhase.WaitingForWifi -> UpdateLine("Waiting for Wi-Fi", megabytes(release.bytes))
    KoneUpdatePhase.Failed -> UpdateLine("Update failed", weather = NodeWeather.Cut, detail = state.message ?: "Update incomplete. Try again.")
    else -> when {
        !state.verified -> UpdateLine("Available", megabytes(release.bytes))
        !state.permissionGranted -> UpdateLine("Needs permission", detail = "Allow KOne to install updates in system settings.")
        else -> UpdateLine("Ready to install")
    }
}

private fun megabytes(bytes: Long) = "${maxOf(1L, (bytes + 524_288) / 1_048_576)} MB"

/** The version, its status and its notes, as the network tests' rows. */
@Composable
internal fun UpdateRows(release: KoneRelease, line: UpdateLine) {
    var reasonOpen by remember(line.title, line.detail) { mutableStateOf(false) }
    var notesOpen by remember(release.versionCode) { mutableStateOf(false) }
    Column(Modifier.dialogBleed().verticalScroll(rememberScrollState())) {
        TestRow("KOne ${release.versionName}", null, value = release.versionCode.toString(), open = false, onToggle = {})
        TestRow(line.title, line.weather, value = line.value, detail = line.detail, open = reasonOpen, onToggle = { reasonOpen = !reasonOpen })
        if (release.notes.isNotBlank()) {
            TestFold("What's new", notesOpen, { notesOpen = !notesOpen })
            AnimatedVisibility(notesOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                TestNote(release.notes.trim(), Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/**
 * A dialog sets its text 24 dp in from its edges; the rows bring that gutter themselves, so they
 * reach back out to the edges, and their titles line up with the dialog's own.
 */
private fun Modifier.dialogBleed(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = (TestGutter * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
