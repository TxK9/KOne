package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.theme.KnineSegmented
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.selfuse.Routing
import io.nekohasekai.sfa.selfuse.RoutingSettings
import kotlinx.coroutines.launch
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

private val REMOTE_NAMES = listOf("Cloudflare", "Google", "Quad9")
private val DIRECT_NAMES = listOf("AliDNS", "DNSPod")

/**
 * Settings → Routing: sites straight out or through the node, Block ads, what nothing matches,
 * the two DNS servers and IPv6. A switch or choice takes effect at once (the running service is
 * reloaded); the site lists when saved. See [Routing] for where they go in the configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.knine_routing)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(RoutingSettings.current()) }
    var direct by remember { mutableStateOf(choices.direct.joinToString("\n")) }
    var proxied by remember { mutableStateOf(choices.proxied.joinToString("\n")) }
    var saved by remember { mutableStateOf(false) }

    fun keep(next: Routing.Choices) {
        choices = next
        scope.launch { RoutingSettings.save(next) }
    }

    fun lines(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    val scaffoldPadding = LocalScaffoldPadding.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(top = scaffoldPadding.calculateTopPadding() + 8.dp, bottom = scaffoldPadding.calculateBottomPadding() + 8.dp),
    ) {
        KnineSectionLabel(text = stringResource(R.string.knine_routing_sites))
        Section {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = direct, onValueChange = { direct = it; saved = false },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_direct)) },
                    supportingText = { Text(stringResource(R.string.knine_routing_direct_hint)) },
                )
                OutlinedTextField(
                    value = proxied, onValueChange = { proxied = it; saved = false },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_proxied)) },
                    supportingText = { Text(stringResource(R.string.knine_routing_proxied_hint)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (saved) Text(stringResource(R.string.knine_routing_saved), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        // Kept as the template takes them: cleaned, once each, a site on both lists straight out.
                        val cleanDirect = Routing.sites(lines(direct))
                        val cleanProxied = Routing.sites(lines(proxied)).filter { it !in cleanDirect }
                        direct = cleanDirect.joinToString("\n")
                        proxied = cleanProxied.joinToString("\n")
                        keep(choices.copy(direct = cleanDirect, proxied = cleanProxied))
                        saved = true
                    }) { Text(stringResource(R.string.knine_routing_save_sites)) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_blocking))
        Section {
            SwitchRow(stringResource(R.string.knine_routing_block_ads), stringResource(R.string.knine_routing_block_ads_detail), choices.blockAds) {
                keep(choices.copy(blockAds = it))
            }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_unmatched))
        Section {
            ChoiceRow(
                detail = stringResource(R.string.knine_routing_unmatched_detail),
                options = listOf(stringResource(R.string.knine_routing_unmatched_proxy), stringResource(R.string.knine_routing_unmatched_direct)),
                selected = if (choices.unmatchedDirect) 1 else 0,
            ) { keep(choices.copy(unmatchedDirect = it == 1)) }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_dns))
        Section {
            ChoiceRow(
                detail = stringResource(R.string.knine_routing_remote_dns),
                options = REMOTE_NAMES,
                selected = Routing.REMOTE.keys.indexOf(choices.remoteDns).coerceAtLeast(0),
            ) { keep(choices.copy(remoteDns = if (it == 0) "" else Routing.REMOTE.keys.elementAt(it))) }
            ChoiceRow(
                detail = stringResource(R.string.knine_routing_direct_dns),
                options = DIRECT_NAMES,
                selected = Routing.DIRECT.keys.indexOf(choices.directDns).coerceAtLeast(0),
            ) { keep(choices.copy(directDns = if (it == 0) "" else Routing.DIRECT.keys.elementAt(it))) }
            SwitchRow(stringResource(R.string.knine_routing_ipv6), stringResource(R.string.knine_routing_ipv6_detail), choices.ipv6) {
                keep(choices.copy(ipv6 = it))
            }
        }

        Text(
            stringResource(R.string.knine_routing_claude_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column { content() } }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(detail, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = { KnineSwitch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ChoiceRow(detail: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        KnineSegmented(options = options, selected = selected, onSelect = onSelect, modifier = Modifier.fillMaxWidth())
    }
}
