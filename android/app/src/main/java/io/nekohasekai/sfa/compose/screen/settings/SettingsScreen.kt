package io.nekohasekai.sfa.compose.screen.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.KnineNavRow
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.managed.ManagedState
import io.nekohasekai.sfa.managed.ManagedStatus
import io.nekohasekai.sfa.update.KoneUpdates
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/** Routes of the pages only the customer edition offers once it is linked to a service. */
internal object AccountRoutes {
    const val Main = "settings/account"
    const val Feedback = "settings/feedback"
}

/** Whether this copy is the customer edition and linked: only then do Account and Feedback lead anywhere. */
internal val accountAvailable: Boolean get() = BuildConfig.KONE_ACTIVATION && ManagedState.snapshot.bound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_settings)) },
        )
    }

    // Either update channel can raise the dot: KOne's own feed or the upstream checker.
    val hasUpstreamUpdate by UpdateState.hasUpdate
    val koneUpdate by KoneUpdates.state.collectAsState()
    val hasUpdate = hasUpstreamUpdate || koneUpdate.release != null
    val hookStatus by HookStatusClient.status.collectAsState()
    val hasPendingPrivilegeDowngrade = HookModuleUpdateNotifier.isDowngrade(hookStatus)
    val hasPendingPrivilegeUpdate = HookModuleUpdateNotifier.isUpgrade(hookStatus)
    LaunchedEffect(Unit) {
        HookStatusClient.refresh()
    }
    val account = ManagedState.snapshot
    val linked = accountAvailable
    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        KnineRowGroup {
            if (linked) {
                KnineNavRow(
                    title = stringResource(R.string.kone_account),
                    icon = Icons.Outlined.AccountCircle,
                    supporting = accountSummary(account.status, account.syncedAt),
                    onClick = { navController.navigate(AccountRoutes.Main) },
                )
            }
            KnineNavRow(
                title = stringResource(R.string.title_app_settings),
                icon = Icons.Outlined.PhoneAndroid,
                supporting = if (hasUpdate) {
                    stringResource(R.string.kone_update_available_badge)
                } else {
                    stringResource(R.string.kone_settings_app_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME)
                },
                badge = if (hasUpdate) MaterialTheme.colorScheme.primary else null,
                badgeDescription = stringResource(R.string.kone_update_available_badge),
                onClick = { navController.navigate("settings/app") },
            )
        }

        KnineSectionLabel(text = stringResource(R.string.kone_settings_connection), modifier = Modifier.padding(top = 8.dp))
        KnineRowGroup {
            KnineNavRow(
                title = stringResource(R.string.service),
                icon = Icons.Outlined.Tune,
                onClick = { navController.navigate("settings/service") },
            )
            KnineNavRow(
                title = stringResource(R.string.knine_routing),
                icon = Icons.AutoMirrored.Outlined.AltRoute,
                onClick = { navController.navigate("settings/routing") },
            )
            KnineNavRow(
                title = stringResource(R.string.profile_override),
                icon = Icons.Outlined.FilterAlt,
                onClick = { navController.navigate("settings/profile_override") },
            )
        }

        KnineSectionLabel(text = stringResource(R.string.kone_settings_advanced), modifier = Modifier.padding(top = 8.dp))
        KnineRowGroup {
            KnineNavRow(
                title = stringResource(R.string.remote_control),
                icon = Icons.Outlined.SettingsRemote,
                onClick = { navController.navigate("settings/remote_control") },
            )
            // Privilege escalation (LSPosed): hides the VPN from chosen Apps such as PayPal; needs root and LSPosed.
            KnineNavRow(
                title = stringResource(R.string.privilege_settings),
                icon = Icons.Outlined.AdminPanelSettings,
                badge = when {
                    hasPendingPrivilegeDowngrade -> MaterialTheme.colorScheme.error
                    hasPendingPrivilegeUpdate -> Color(0xFFFFC107)
                    else -> null
                },
                badgeDescription = stringResource(R.string.kone_privilege_update_badge),
                onClick = { navController.navigate("settings/privilege") },
            )
        }

        // Feedback reaches the service that issued the access key, so only a linked copy offers it.
        if (linked) {
            KnineSectionLabel(text = stringResource(R.string.kone_settings_help), modifier = Modifier.padding(top = 8.dp))
            KnineRowGroup {
                KnineNavRow(
                    title = stringResource(R.string.kone_feedback),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = { navController.navigate(AccountRoutes.Feedback) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** "Synced · 5 minutes ago" while all is well; otherwise the state alone, which says enough. */
@Composable
private fun accountSummary(status: ManagedStatus, syncedAt: Long): String {
    if (status != ManagedStatus.Synced || syncedAt <= 0L) return status.label
    val ago = DateUtils.getRelativeTimeSpanString(syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return "${status.label} · $ago"
}
