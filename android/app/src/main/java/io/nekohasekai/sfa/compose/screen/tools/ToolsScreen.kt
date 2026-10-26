package io.nekohasekai.sfa.compose.screen.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.KnineChevron
import io.nekohasekai.sfa.compose.component.KnineNavRow
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.component.RemoteControlMenuItems
import io.nekohasekai.sfa.compose.component.rememberRemoteServers
import io.nekohasekai.sfa.compose.navigation.DeviceRoutes
import io.nekohasekai.sfa.compose.navigation.NetworkTestRoutes
import io.nekohasekai.sfa.compose.screen.console.ConsoleActivity
import io.nekohasekai.sfa.compose.screen.router.RouterActivity
import io.nekohasekai.sfa.compose.screen.usbip.USBIPStatusViewModel
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.devices.DeviceIntegrations
import io.nekohasekai.sfa.devices.DeviceManagementGate
import io.nekohasekai.sfa.selfuse.HomeSettings
import io.nekohasekai.sfa.selfuse.PairedProfile
import io.nekohasekai.sfa.terminal.DEFAULT_SSH_TERMINAL_TYPE
import io.nekohasekai.sfa.terminal.TailscaleSSHPresentedSession
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineBadge as Badge
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    navController: NavController,
    tailscaleViewModel: TailscaleStatusViewModel,
    sshSharedViewModel: TailscaleSSHSharedViewModel,
    usbIPViewModel: USBIPStatusViewModel,
    openConnectViewModel: OpenConnectStatusViewModel,
    openVPNViewModel: OpenVPNStatusViewModel,
    showStatusBar: Boolean = false,
    serviceStatus: Status = Status.Stopped,
) {
    val remoteServers by rememberRemoteServers()

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_tools)) },
            actions = {
                if (remoteServers.isNotEmpty()) {
                    Box {
                        var showOthersMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showOthersMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.title_others),
                            )
                        }
                        DropdownMenu(
                            expanded = showOthersMenu,
                            onDismissRequest = { showOthersMenu = false },
                        ) {
                            RemoteControlMenuItems(
                                servers = remoteServers,
                                onAction = { showOthersMenu = false },
                                leadingDivider = false,
                            )
                        }
                    }
                }
            },
        )
    }

    val tailscaleState by tailscaleViewModel.uiState.collectAsState()
    val taildropSendSessions by TaildropSendManager.sessions.collectAsState()
    val usbIPState by usbIPViewModel.uiState.collectAsState()
    val openConnectState by openConnectViewModel.uiState.collectAsState()
    val openVPNState by openVPNViewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(LocalScaffoldPadding.current)
            .verticalScroll(rememberScrollState())
            // The last row can always scroll clear of the start action floating over the corner.
            .padding(top = 8.dp, bottom = (if (showStatusBar) STATUS_BAR_CONTENT_PADDING else 8.dp) + START_ACTION_CLEARANCE),
    ) {
        val tailscaleEndpoints = tailscaleState.endpoints
        val openConnectEndpoints = openConnectState.endpoints
        val openVPNEndpoints = openVPNState.endpoints
        val tailscaleTags = tailscaleEntryTags(tailscaleEndpoints.map { it.endpointTag }, local = remoteServer == null)
        if (tailscaleTags.size + openConnectEndpoints.size + openVPNEndpoints.size > 0) {
            KnineSectionLabel(text = stringResource(R.string.tailscale_endpoints))
            // The same card and rows as every other group here, so the hub reads as one list.
            KnineRowGroup {
                tailscaleTags.forEachIndexed { index, endpointTag ->
                    // Keyed by tag, so a menu left open belongs to its own endpoint when the list changes.
                    key(endpointTag) {
                        val endpoint = tailscaleEndpoints.firstOrNull { it.endpointTag == endpointTag }
                        var showSSHMenu by remember { mutableStateOf(false) }
                        val sshPeers = remember(endpoint) {
                            endpoint?.userGroups.orEmpty().flatMap { it.peers }.filter { peer ->
                                peer.online && peer.sshHostKeys.isNotEmpty() &&
                                    peer.tailscaleIPs.isNotEmpty() && peer.id != endpoint?.selfPeer?.id
                            }
                        }
                        val unread = endpoint?.unreadFileCount ?: 0
                        Box {
                            ToolRow(
                                title = numbered(stringResource(R.string.tailscale), index, tailscaleTags.size),
                                icon = Icons.Outlined.Hub,
                                supporting = if (remoteServer == null && endpointTag == PairedProfile.HOME_TAG) {
                                    tailscaleConnectionLabel(HomeSettings.isEnabled, serviceStatus, endpoint?.backendState, tailscaleState.hasUpdate)
                                } else {
                                    endpoint?.stateText.orEmpty()
                                },
                                onClick = { navController.navigate("tools/tailscale/${Uri.encode(endpointTag)}") },
                                onLongClick = if (sshPeers.isNotEmpty()) ({ showSSHMenu = true }) else null,
                                onLongClickLabel = stringResource(R.string.tailscale_ssh_connect),
                            ) {
                                when {
                                    taildropSendSessions.any { it.endpointTag == endpointTag && it.errorMessage != null } ->
                                        Badge(containerColor = MaterialTheme.colorScheme.error) { Text("!") }
                                    unread > 0 -> Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$unread") }
                                    else -> KnineChevron()
                                }
                            }
                            DropdownMenu(
                                expanded = showSSHMenu,
                                onDismissRequest = { showSSHMenu = false },
                            ) {
                                if (sshPeers.size == 1) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tailscale_ssh_connect)) },
                                        leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                                        onClick = {
                                            showSSHMenu = false
                                            handleSSHNavigation(navController, sshSharedViewModel, sshPeers[0], endpointTag)
                                        },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tailscale_ssh_connect)) },
                                        leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                                        enabled = false,
                                        onClick = {},
                                    )
                                    sshPeers.forEach { peer ->
                                        DropdownMenuItem(
                                            text = { Text(peer.hostName) },
                                            onClick = {
                                                showSSHMenu = false
                                                handleSSHNavigation(navController, sshSharedViewModel, peer, endpointTag)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                openConnectEndpoints.forEachIndexed { index, endpoint ->
                    KnineNavRow(
                        title = numbered(stringResource(R.string.openconnect), index, openConnectEndpoints.size),
                        icon = Icons.Outlined.Route,
                        onClick = { navController.navigate("tools/openconnect/${Uri.encode(endpoint.endpointTag)}") },
                    )
                }
                openVPNEndpoints.forEachIndexed { index, endpoint ->
                    KnineNavRow(
                        title = numbered(stringResource(R.string.openvpn), index, openVPNEndpoints.size),
                        icon = Icons.Outlined.Route,
                        onClick = { navController.navigate("tools/openvpn/${Uri.encode(endpoint.endpointTag)}") },
                    )
                }
            }
        }

        val servers = usbIPState.servers
        if (servers.isNotEmpty()) {
            KnineSectionLabel(text = stringResource(R.string.title_services))
            KnineRowGroup {
                servers.forEachIndexed { index, server ->
                    KnineNavRow(
                        title = numbered(stringResource(R.string.title_usbip), index, servers.size),
                        icon = Icons.Default.Usb,
                        onClick = { navController.navigate("tools/usbip/${Uri.encode(server.serverTag)}") },
                    )
                }
            }
        }

        // The private management console is available only in the self-use build. With devices
        // shown it joins them under one heading rather than standing alone under its own.
        val consoleShown = BuildConfig.KNINE_OWNER && BuildConfig.KONE_ADMIN.isNotBlank()
        val consoleRow: @Composable () -> Unit = {
            KnineNavRow(
                title = stringResource(R.string.kone_tools_console),
                icon = Icons.Outlined.Apartment,
                onClick = { context.startActivity(Intent(context, ConsoleActivity::class.java)) },
            )
        }
        if (consoleShown && !DeviceManagementGate.visible) {
            KnineSectionLabel(text = stringResource(R.string.kone_tools_management))
            KnineRowGroup { consoleRow() }
        }

        // Devices and their engines; the edition for others gains it once the console grants it.
        if (DeviceManagementGate.visible) {
            val integrations by DeviceIntegrations.registry.integrations.collectAsState()
            KnineSectionLabel(text = stringResource(R.string.kone_devices))
            KnineRowGroup {
                if (consoleShown) consoleRow()
                if (integrations.isNotEmpty()) {
                    KnineNavRow(
                        title = stringResource(R.string.kone_tools_devices),
                        icon = Icons.Outlined.Devices,
                        onClick = { navController.navigate(DeviceRoutes.Main) },
                    )
                }
                KnineNavRow(
                    title = stringResource(R.string.kone_router),
                    icon = Icons.Outlined.Router,
                    onClick = { context.startActivity(Intent(context, RouterActivity::class.java)) },
                )
            }
        }

        KnineSectionLabel(text = stringResource(R.string.title_network))
        KnineRowGroup {
            KnineNavRow(
                title = stringResource(R.string.knine_diagnose),
                icon = Icons.Outlined.HealthAndSafety,
                onClick = { navController.navigate("tools/diagnose") },
            )
            KnineNavRow(
                title = stringResource(R.string.kone_tools_network_tests),
                icon = Icons.Outlined.NetworkCheck,
                onClick = { navController.navigate(NetworkTestRoutes.Main) },
            )
        }
    }
}

/** "Tailscale" when there is one, "Tailscale 2" among several. */
private fun numbered(name: String, index: Int, count: Int): String = if (count == 1) name else "$name ${index + 1}"

/**
 * [KnineNavRow] for an endpoint: the same icon, lines and trailing slot, with a long press for
 * its shortcuts and a [trailing] count or warning where the chevron would be.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(
    title: String,
    icon: ImageVector,
    supporting: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String,
    trailing: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supporting.takeIf { it.isNotEmpty() }?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box(Modifier.defaultMinSize(24.dp, 24.dp), contentAlignment = Alignment.Center) { trailing() }
        },
        modifier = Modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = if (onLongClick != null) onLongClickLabel else null,
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private val STATUS_BAR_CONTENT_PADDING = 8.dp

/** Room under a list for the start action's line, so it never sits on the last row. */
internal val START_ACTION_CLEARANCE = 80.dp

internal fun handleSSHNavigation(
    navController: NavController,
    sshSharedViewModel: TailscaleSSHSharedViewModel,
    peer: TailscalePeerData,
    endpointTag: String,
) {
    val quickConnectPeers = Settings.tailscaleSSHQuickConnectPeers
    if (quickConnectPeers.contains(peer.stableID)) {
        val usernames = Settings.tailscaleSSHRememberedUsernames
        val terminalTypes = Settings.tailscaleSSHRememberedTerminalTypes
        sshSharedViewModel.setPendingSession(
            TailscaleSSHPresentedSession(
                endpointTag = endpointTag,
                peerHostName = peer.hostName,
                peerAddress = peer.tailscaleIPs.first(),
                username = usernames[peer.stableID]?.takeIf { it.isNotBlank() } ?: DEFAULT_SSH_USERNAME,
                terminalType = terminalTypes[peer.stableID]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_SSH_TERMINAL_TYPE,
                hostKeys = peer.sshHostKeys,
            ),
        )
        navController.navigate(
            "tools/tailscale/${Uri.encode(endpointTag)}/peer/${Uri.encode(peer.id)}/terminal",
        )
    } else {
        navController.navigate(
            "tools/tailscale/${Uri.encode(endpointTag)}/peer/${Uri.encode(peer.id)}/ssh",
        )
    }
}
