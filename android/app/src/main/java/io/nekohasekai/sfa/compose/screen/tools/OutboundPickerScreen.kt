package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OutboundPickerViewModel :
    ViewModel(),
    CommandClient.Handler {
    private val _outbounds = MutableStateFlow<List<GroupItem>>(emptyList())
    val outbounds: StateFlow<List<GroupItem>> = _outbounds.asStateFlow()

    private var commandClient: CommandClient? = null

    fun connect() {
        disconnect()
        commandClient = CommandClient(
            viewModelScope,
            CommandClient.ConnectionType.Outbounds,
            this,
        )
        commandClient?.connect()
    }

    fun disconnect() {
        commandClient?.disconnect()
        commandClient = null
    }

    override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
        _outbounds.value = outbounds.map { GroupItem(it) }
    }

    override fun onCleared() {
        disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboundPickerScreen(
    navController: NavController,
    selectedOutbound: String,
) {
    val viewModel: OutboundPickerViewModel = viewModel()
    val outbounds by viewModel.outbounds.collectAsState()

    DisposableEffect(Unit) {
        viewModel.connect()
        onDispose {
            viewModel.disconnect()
        }
    }

    fun selectOutbound(tag: String) {
        navController.previousBackStackEntry?.savedStateHandle?.set("selected_outbound", tag)
        navController.navigateUp()
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.tool_outbound)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                    )
                }
            },
        )
    }

    OutboundPickerContent(outbounds, selectedOutbound, ::selectOutbound)
}

/** Only numbered labels are public; selecting one still returns its exact service tag. */
@Composable
internal fun OutboundPickerContent(
    outbounds: List<GroupItem>,
    selectedOutbound: String,
    onSelected: (String) -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    val filteredOutbounds = outbounds.mapIndexed { index, item -> index to item }
        .filter { (index, _) -> "Route ${index + 1}".contains(searchText.trim(), ignoreCase = true) }
    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(android.R.string.search_go)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = if (searchText.isNotEmpty()) {
                { IconButton(onClick = { searchText = "" }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear)) } }
            } else null,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OutboundPickerItem(
                    label = stringResource(R.string.tool_default_outbound),
                    isSelected = selectedOutbound.isEmpty(),
                    onClick = { onSelected("") },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            items(filteredOutbounds, key = { it.second.tag }) { (index, item) ->
                OutboundPickerItem(
                    label = "Route ${index + 1}",
                    type = "Available route",
                    urlTestDelay = item.urlTestDelay,
                    isSelected = selectedOutbound == item.tag,
                    onClick = { onSelected(item.tag) },
                )
            }
            if (filteredOutbounds.isEmpty() && searchText.isNotBlank()) {
                item {
                    ToolEmptyState(stringResource(R.string.knine_no_search_results), message = stringResource(R.string.knine_no_search_results_hint))
                }
            }
        }
    }
}

@Composable
private fun OutboundPickerItem(
    label: String,
    type: String? = null,
    urlTestDelay: Int = 0,
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (type != null) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = type,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (urlTestDelay > 0) {
                        Text(
                            text = "${urlTestDelay} ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = outboundDelayColor(urlTestDelay),
                        )
                    }
                }
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun OutboundPickerRow(
    selectedOutbound: String,
    onClick: () -> Unit,
) {
    val displayText = if (selectedOutbound.isEmpty()) {
        stringResource(R.string.tool_default_outbound)
    } else {
        "Route selected"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolDetailValue(stringResource(R.string.tool_outbound), displayText, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun outboundDelayColor(delay: Int): Color {
    val colorScheme = MaterialTheme.colorScheme
    return when {
        delay < 100 -> colorScheme.tertiary
        delay < 300 -> colorScheme.primary
        delay < 500 -> colorScheme.secondary
        else -> colorScheme.error
    }
}
