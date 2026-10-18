package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleExitNodePickerScreen(
    navController: NavController,
    viewModel: TailscaleStatusViewModel,
    endpointTag: String,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.tailscale_exit_node)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val state by viewModel.uiState.collectAsState()
    val endpoint = state.endpoints.firstOrNull { it.endpointTag == endpointTag }

    if (endpoint == null) {
        LaunchedEffect(Unit) {
            navController.navigateUp()
        }
        return
    }

    var searchText by rememberSaveable { mutableStateOf("") }

    val selfStableID = endpoint.selfPeer?.stableID
    val candidates = endpoint.userGroups
        .flatMap { it.peers }
        .filter { it.exitNodeOption && it.stableID != selfStableID }
        .filter { searchText.isEmpty() || it.displayName.contains(searchText, ignoreCase = true) || it.hostName.contains(searchText, ignoreCase = true) }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(android.R.string.search_go)) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = if (searchText.isNotEmpty()) {
                { IconButton(onClick = { searchText = "" }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear)) } }
            } else null,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                DisabledRow(
                    isSelected = endpoint.exitNode == null,
                    onClick = {
                        viewModel.setExitNode(endpointTag, "")
                        navController.navigateUp()
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            items(candidates, key = { it.stableID }) { peer ->
                PeerRow(
                    peer = peer,
                    isSelected = endpoint.exitNode?.stableID == peer.stableID,
                    onClick = {
                        viewModel.setExitNode(endpointTag, peer.stableID)
                        navController.navigateUp()
                    },
                )
            }
            if (candidates.isEmpty() && searchText.isNotBlank()) {
                item { ToolEmptyState(stringResource(R.string.knine_no_search_results), message = stringResource(R.string.knine_no_search_results_hint)) }
            }
        }
    }
}

@Composable
private fun DisabledRow(
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.disabled),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PeerRow(
    peer: TailscalePeerData,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (peer.online) Color(0xFF4CAF50) else Color.Gray),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = peer.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
            val firstIP = peer.tailscaleIPs.firstOrNull()
            if (firstIP != null) {
                Text(
                    text = firstIP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
