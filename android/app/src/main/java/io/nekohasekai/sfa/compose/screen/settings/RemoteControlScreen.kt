package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SettingsRemote
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.theme.KnineEmptyState
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.RemoteServer
import io.nekohasekai.sfa.database.RemoteServerManager
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    // Null until read, so the empty page does not flash up before the list.
    var servers by remember { mutableStateOf<List<RemoteServer>?>(null) }
    val activeRemoteServer by RemoteControlManager.remoteServer.collectAsState()

    LaunchedEffect(Unit) {
        servers = withContext(Dispatchers.IO) { RemoteServerManager.list() }
    }
    DisposableEffect(Unit) {
        val callback: () -> Unit = {
            scope.launch {
                servers = withContext(Dispatchers.IO) { RemoteServerManager.list() }
            }
        }
        RemoteServerManager.registerCallback(callback)
        onDispose {
            RemoteServerManager.unregisterCallback(callback)
        }
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.remote_control)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
            actions = {
                // With no servers the page itself offers to add one; a second button above it would repeat it.
                if (!servers.isNullOrEmpty()) {
                    IconButton(onClick = { navController.navigate("settings/remote_control/new") }) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.remote_new_server),
                        )
                    }
                }
            },
        )
    }

    val scaffoldPadding = LocalScaffoldPadding.current
    val list = servers ?: return

    if (list.isEmpty()) {
        KnineEmptyState(
            title = stringResource(R.string.remote_no_servers),
            icon = Icons.Outlined.SettingsRemote,
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            action = {
                TextButton(onClick = { navController.navigate("settings/remote_control/new") }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.remote_new_server), modifier = Modifier.padding(start = 8.dp))
                }
            },
        )
        return
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        Spacer(Modifier.height(8.dp))
        KnineRowGroup {
            list.forEach { server ->
                key(server.id) {
                    ServerRow(
                        server = server,
                        active = activeRemoteServer?.id == server.id,
                        onEdit = { navController.navigate("settings/remote_control/edit/${server.id}") },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                if (RemoteControlManager.remoteServer.value?.id == server.id) {
                                    withContext(Dispatchers.Main) {
                                        RemoteControlManager.exitRemoteControl()
                                    }
                                }
                                RemoteServerManager.delete(server)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One server: its name and address; a tap edits it, a long press offers Edit and Delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(server: RemoteServer, active: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(server.displayName, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = if (server.name.isNotEmpty()) {
                { Text(server.url, style = MaterialTheme.typography.bodyMedium) }
            } else {
                null
            },
            leadingContent = {
                Icon(imageVector = Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = if (active) {
                { Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
            modifier = Modifier.combinedClickable(onClick = onEdit, onLongClick = { showMenu = true }),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}
