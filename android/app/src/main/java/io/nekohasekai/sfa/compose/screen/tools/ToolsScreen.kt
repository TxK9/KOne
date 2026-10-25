package io.nekohasekai.sfa.compose.screen.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SwapHoriz
import io.nekohasekai.sfa.compose.theme.KnineBadge as Badge
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import androidx.compose.material3.CardDefaults
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import io.nekohasekai.sfa.compose.component.RemoteControlMenuItems
import io.nekohasekai.sfa.compose.component.rememberRemoteServers
import io.nekohasekai.sfa.compose.navigation.DeviceRoutes
import io.nekohasekai.sfa.compose.navigation.NetworkTestRoutes
import io.nekohasekai.sfa.compose.screen.console.ConsoleActivity
import io.nekohasekai.sfa.compose.screen.usbip.USBIPStatusViewModel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.selfuse.HomeSettings
import io.nekohasekai.sfa.selfuse.PairedProfile
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.devices.DeviceManagementGate
import io.nekohasekai.sfa.terminal.DEFAULT_SSH_TERMINAL_TYPE
import io.nekohasekai.sfa.terminal.TailscaleSSHPresentedSession
import io.nekohasekai.sfa.utils.RemoteControlManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    val crashUnreadCount by CrashReportManager.unreadCount.collectAsState()
    val oomUnreadCount by OOMReportManager.unreadCount.collectAsState()
    val powerUnreadCount by PowerReportManager.unreadCount.collectAsState()
    val tailscaleState by tailscaleViewModel.uiState.collectAsState()
    val taildropSendSessions by TaildropSendManager.sessions.collectAsState()
    val usbIPState by usbIPViewModel.uiState.collectAsState()
    val openConnectState by openConnectViewModel.uiState.collectAsState()
    val openVPNState by openVPNViewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(scaffoldPadding)
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = if (showStatusBar) STATUS_BAR_CONTENT_PADDING else 8.dp),
    ) {
        val tailscaleEndpoints = tailscaleState.endpoints
        val openConnectEndpoints = openConnectState.endpoints
        val openVPNEndpoints = openVPNState.endpoints
        val tailscaleTags = tailscaleEntryTags(tailscaleEndpoints.map { it.endpointTag }, local = remoteServer == null)
        val endpointRowCount = tailscaleTags.size + openConnectEndpoints.size + openVPNEndpoints.size
        if (endpointRowCount > 0) {
            io.nekohasekai.sfa.compose.theme.KnineSectionLabel(text = stringResource(R.string.tailscale_endpoints))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                tailscaleTags.forEachIndexed { index, endpointTag ->
                    val endpoint = tailscaleEndpoints.firstOrNull { it.endpointTag == endpointTag }
                    val shape = endpointRowShape(index, endpointRowCount)
                    var showSSHMenu by remember { mutableStateOf(false) }
                    val sshPeers = remember(endpoint) {
                        endpoint?.userGroups.orEmpty().flatMap { it.peers }.filter { peer ->
                            peer.online && peer.sshHostKeys.isNotEmpty() &&
                                peer.tailscaleIPs.isNotEmpty() && peer.id != endpoint?.selfPeer?.id
                        }
                    }
                    Box {
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (tailscaleTags.size == 1) {
                                        stringResource(R.string.tailscale)
                                    } else {
                                        stringResource(R.string.tailscale) + " ${index + 1}"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (remoteServer == null && endpointTag == PairedProfile.HOME_TAG) {
                                        tailscaleConnectionLabel(HomeSettings.isEnabled, serviceStatus, endpoint?.backendState, tailscaleState.hasUpdate)
                                    } else endpoint?.stateText.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                if (taildropSendSessions.any { it.endpointTag == endpointTag && it.errorMessage != null }) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                                        Text("!")
                                    }
                                } else if ((endpoint?.unreadFileCount ?: 0) > 0) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text("${endpoint?.unreadFileCount}")
                                    }
                                }
                            },
                            modifier = Modifier
                                .clip(shape)
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("tools/tailscale/${Uri.encode(endpointTag)}")
                                    },
                                    onLongClick = {
                                        if (sshPeers.isNotEmpty()) {
                                            showSSHMenu = true
                                        }
                                    },
                                ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        DropdownMenu(
                            expanded = showSSHMenu,
                            onDismissRequest = { showSSHMenu = false },
                        ) {
                            if (sshPeers.size == 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tailscale_ssh_connect)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Terminal, contentDescription = null)
                                    },
                                    onClick = {
                                        showSSHMenu = false
                                        handleSSHNavigation(
                                            navController,
                                            sshSharedViewModel,
                                            sshPeers[0],
                                            endpointTag,
                                        )
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tailscale_ssh_connect)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Terminal, contentDescription = null)
                                    },
                                    enabled = false,
                                    onClick = {},
                                )
                                sshPeers.forEach { peer ->
                                    DropdownMenuItem(
                                        text = { Text(peer.hostName) },
                                        onClick = {
                                            showSSHMenu = false
                                            handleSSHNavigation(
                                                navController,
                                                sshSharedViewModel,
                                                peer,
                                                endpointTag,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                openConnectEndpoints.forEachIndexed { index, endpoint ->
                    ListItem(
                        headlineContent = {
                            Text(
                                if (openConnectEndpoints.size == 1) {
                                    stringResource(R.string.openconnect)
                                } else {
                                    stringResource(R.string.openconnect) + " ${index + 1}"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Route,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier
                            .clip(endpointRowShape(tailscaleTags.size + index, endpointRowCount))
                            .clickable {
                                navController.navigate("tools/openconnect/${Uri.encode(endpoint.endpointTag)}")
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                openVPNEndpoints.forEachIndexed { index, endpoint ->
                    ListItem(
                        headlineContent = {
                            Text(
                                if (openVPNEndpoints.size == 1) {
                                    stringResource(R.string.openvpn)
                                } else {
                                    stringResource(R.string.openvpn) + " ${index + 1}"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Route,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier
                            .clip(
                                endpointRowShape(
                                    tailscaleTags.size + openConnectEndpoints.size + index,
                                    endpointRowCount,
                                ),
                            )
                            .clickable {
                                navController.navigate("tools/openvpn/${Uri.encode(endpoint.endpointTag)}")
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        if (usbIPState.servers.isNotEmpty()) {
            io.nekohasekai.sfa.compose.theme.KnineSectionLabel(text = stringResource(R.string.title_services))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                val servers = usbIPState.servers
                servers.forEachIndexed { index, server ->
                    val shape = when {
                        servers.size == 1 -> RoundedCornerShape(12.dp)
                        index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        index == servers.size - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                if (servers.size == 1) {
                                    stringResource(R.string.title_usbip)
                                } else {
                                    stringResource(R.string.title_usbip) + " ${index + 1}"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier
                            .clip(shape)
                            .clickable {
                                navController.navigate("tools/usbip/${Uri.encode(server.serverTag)}")
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        // The private management console is available only in the self-use build.
        if (BuildConfig.KNINE_OWNER && BuildConfig.KONE_ADMIN.isNotBlank()) {
            val context = LocalContext.current
            io.nekohasekai.sfa.compose.theme.KnineSectionLabel(text = "Management")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            "Management console",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Apartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { context.startActivity(Intent(context, ConsoleActivity::class.java)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        // Devices and their engines; the edition for others gains it once the console grants it.
        if (DeviceManagementGate.visible) {
            val routerContext = LocalContext.current
            io.nekohasekai.sfa.compose.theme.KnineSectionLabel(text = "Devices")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            "Device management",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { navController.navigate(DeviceRoutes.Main) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.kone_router)) },
                    leadingContent = { Icon(Icons.Outlined.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        routerContext.startActivity(Intent(routerContext, io.nekohasekai.sfa.compose.screen.router.RouterActivity::class.java))
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        io.nekohasekai.sfa.compose.theme.KnineSectionLabel(text = stringResource(R.string.title_network))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.knine_diagnose),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.HealthAndSafety,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { navController.navigate("tools/diagnose") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = {
                    Text(
                        "Network tests",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.NetworkCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { navController.navigate(NetworkTestRoutes.Main) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }


    }
}

private val STATUS_BAR_CONTENT_PADDING = 8.dp

private fun endpointRowShape(index: Int, count: Int): RoundedCornerShape = when {
    count == 1 -> RoundedCornerShape(12.dp)
    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    index == count - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    else -> RoundedCornerShape(0.dp)
}

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
