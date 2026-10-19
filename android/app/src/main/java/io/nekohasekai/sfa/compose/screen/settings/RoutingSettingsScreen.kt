package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.theme.KnineSegmented
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.ktx.clipboardText
import cc.txwd.kone.shared.Categories
import cc.txwd.kone.shared.Hosts
import cc.txwd.kone.shared.ListStatus
import cc.txwd.kone.shared.NameServers
import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.Region
import io.nekohasekai.sfa.selfuse.CategoryListKeeper
import io.nekohasekai.sfa.selfuse.OwnRules
import io.nekohasekai.sfa.selfuse.PrivateDns
import io.nekohasekai.sfa.selfuse.Routing
import io.nekohasekai.sfa.selfuse.RoutingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

private val REMOTE_NAMES = listOf("Cloudflare", "Google", "Quad9")
/** What each direct preset is called on screen. */
private val DIRECT_NAMES = mapOf("alidns" to "AliDNS", "dnspod" to "DNSPod", "cloudflare" to "Cloudflare", "google" to "Google", "quad9" to "Quad9")
private val KINDS = listOf("domain", "suffix", "keyword", "cidr", "app", "port", "set")

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
    var hosts by remember { mutableStateOf(RoutingSettings.hostLines(choices.hosts)) }
    var nameServers by remember { mutableStateOf(RoutingSettings.nameServerLines(choices.nameServers)) }
    var namesSaved by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val privateDns = remember { PrivateDns.hostname(context) }

    // How each category list the rules name stands: read off the main thread, again after each save.
    var lists by remember { mutableStateOf<Map<String, ListStatus>>(emptyMap()) }
    LaunchedEffect(Unit) { lists = withContext(Dispatchers.IO) { CategoryListKeeper.status() } }

    fun keep(next: Routing.Choices) {
        choices = next
        scope.launch {
            RoutingSettings.save(next)
            lists = withContext(Dispatchers.IO) { CategoryListKeeper.status() }
        }
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
        KnineSectionLabel(text = stringResource(R.string.knine_routing_rules))
        Section {
            RulesEditor(choices.rules, lists, onImport = {
                // Another client's rules, from the clipboard: Clash's rules: lines or v2rayN's JSON.
                val imported = Routing.importRules(choices, clipboardText.orEmpty())
                if (imported.choices != choices) keep(imported.choices)
                imported.said
            }) { keep(choices.copy(rules = it)) }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_blocking))
        Section {
            SwitchRow(stringResource(R.string.knine_routing_block_ads), stringResource(R.string.knine_routing_block_ads_detail), choices.blockAds) {
                keep(choices.copy(blockAds = it))
            }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_choosing))
        Section {
            SwitchRow(stringResource(R.string.knine_routing_automatic), stringResource(R.string.knine_routing_automatic_detail), choices.automatic) {
                keep(choices.copy(automatic = it))
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
            val china = Region.china(choices.region)
            ChoiceRow(
                detail = stringResource(R.string.knine_routing_region_detail),
                options = listOf(stringResource(R.string.knine_routing_region_global), stringResource(R.string.knine_routing_region_china)),
                selected = if (china) 1 else 0,
            ) { picked ->
                // A preset straight-out server follows the region to its default.
                val region = if (picked == 1) Region.CHINA else Region.GLOBAL
                keep(choices.copy(region = region, directDns = choices.directDns.takeUnless { it in Region.DIRECT }.orEmpty()))
            }
            ChoiceRow(
                detail = stringResource(R.string.knine_routing_remote_dns),
                options = REMOTE_NAMES,
                selected = Routing.REMOTE.keys.indexOf(choices.remoteDns).coerceAtLeast(0),
            ) { keep(choices.copy(remoteDns = if (it == 0) "" else Routing.REMOTE.keys.elementAt(it))) }
            // The region's presets, its default first (kept as "" so it follows the region).
            val presets = Region.directPresets(choices.region).take(3)
            ChoiceRow(
                detail = stringResource(R.string.knine_routing_direct_dns),
                options = presets.map { DIRECT_NAMES.getValue(it) },
                selected = presets.indexOf(choices.directDns).coerceAtLeast(0),
            ) { keep(choices.copy(directDns = if (it == 0) "" else presets[it])) }
            SwitchRow(stringResource(R.string.knine_routing_ipv6), stringResource(R.string.knine_routing_ipv6_detail), choices.ipv6) {
                keep(choices.copy(ipv6 = it))
            }
            SwitchRow(stringResource(R.string.knine_routing_fake_ip), stringResource(R.string.knine_routing_fake_ip_detail), choices.fakeIp) {
                keep(choices.copy(fakeIp = it))
            }
            // Android's Private DNS set to a provider answers apps past these servers: said where they are chosen.
            if (privateDns != null) {
                Text(
                    stringResource(R.string.knine_routing_private_dns, privateDns.ifEmpty { "a provider" }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_names))
        Section {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hosts, onValueChange = { hosts = it; namesSaved = null },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_hosts)) },
                    supportingText = { Text(stringResource(R.string.knine_routing_hosts_hint)) },
                )
                OutlinedTextField(
                    value = nameServers, onValueChange = { nameServers = it; namesSaved = null },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_name_servers)) },
                    supportingText = { Text(stringResource(R.string.knine_routing_name_servers_hint)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    namesSaved?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
                    TextButton(onClick = {
                        // Kept as the template takes them; a line that is not a name and an address is left out and said.
                        val hostLines = lines(hosts)
                        val serverLines = lines(nameServers)
                        val keptHosts = hostLines.mapNotNull(Hosts::parse)
                        val keptServers = serverLines.mapNotNull(NameServers::parse)
                        hosts = RoutingSettings.hostLines(keptHosts)
                        nameServers = RoutingSettings.nameServerLines(keptServers)
                        val left = hostLines.size - keptHosts.size + serverLines.size - keptServers.size
                        keep(choices.copy(hosts = keptHosts, nameServers = keptServers))
                        namesSaved = if (left > 0) context.getString(R.string.knine_routing_names_left, left) else context.getString(R.string.knine_routing_saved)
                    }) { Text(stringResource(R.string.knine_routing_save_names)) }
                }
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

@Composable
private fun kindName(kind: String): String = stringResource(
    when (kind) {
        "domain" -> R.string.knine_routing_kind_domain
        "suffix" -> R.string.knine_routing_kind_suffix
        "keyword" -> R.string.knine_routing_kind_keyword
        "cidr" -> R.string.knine_routing_kind_cidr
        "app" -> R.string.knine_routing_kind_app
        "set" -> R.string.knine_routing_kind_set
        else -> R.string.knine_routing_kind_port
    },
)

@Composable
private fun actionName(action: String): String = stringResource(
    when (action) {
        "direct" -> R.string.knine_routing_action_direct
        "block" -> R.string.knine_routing_action_block
        else -> R.string.knine_routing_action_proxy
    },
)

@Composable
private fun kindHint(kind: String): String = stringResource(
    when (kind) {
        "domain" -> R.string.knine_routing_rule_hint_domain
        "suffix" -> R.string.knine_routing_rule_hint_suffix
        "keyword" -> R.string.knine_routing_rule_hint_keyword
        "cidr" -> R.string.knine_routing_rule_hint_cidr
        "app" -> R.string.knine_routing_rule_hint_app
        "set" -> R.string.knine_routing_rule_hint_set
        else -> R.string.knine_routing_rule_hint_port
    },
)

/**
 * The person's rules in order, each movable up and removable, and a form to add one at the end.
 * A rule on a category list says when its list is not on the phone yet ([lists]), and why.
 */
@Composable
private fun RulesEditor(rules: List<Rule>, lists: Map<String, ListStatus>, onImport: () -> String, onChange: (List<Rule>) -> Unit) {
    var said by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(0) }
    var action by remember { mutableStateOf(0) }
    var value by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.knine_routing_rules_detail), style = MaterialTheme.typography.bodyMedium)
        if (rules.isEmpty()) Text(stringResource(R.string.knine_routing_rules_none), style = MaterialTheme.typography.bodyMedium)
        rules.forEachIndexed { index, rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val shown = if (rule.kind == "set") Categories.title(rule.value) ?: rule.value else rule.value
                    Text("${kindName(rule.kind)} · $shown → ${actionName(rule.action)}", style = MaterialTheme.typography.bodyLarge)
                    val status = lists[rule.value]
                    if (rule.kind == "set" && status?.kept != true) {
                        val waiting = status?.error?.let { stringResource(R.string.knine_routing_list_failed, it) } ?: stringResource(R.string.knine_routing_list_waiting)
                        Text(waiting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = {
                    if (index > 0) onChange(rules.toMutableList().also { it[index] = it[index - 1].also { _ -> it[index - 1] = rule } })
                }, enabled = index > 0) {
                    Icon(imageVector = Icons.Outlined.KeyboardArrowUp, contentDescription = null)
                }
                IconButton(onClick = { onChange(rules.filterIndexed { i, _ -> i != index }) }) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = null)
                }
            }
        }
        KnineSegmented(options = KINDS.map { kindName(it) }, selected = kind, onSelect = { kind = it; wrong = false }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = value, onValueChange = { value = it; wrong = false },
            modifier = Modifier.fillMaxWidth(), singleLine = true, isError = wrong,
            label = { Text(stringResource(R.string.knine_routing_rule_value)) },
            supportingText = { Text(if (wrong) stringResource(R.string.knine_routing_rule_wrong) else kindHint(KINDS[kind])) },
        )
        if (KINDS[kind] == "set") CategoryPicker(picked = value.trim().lowercase()) { value = it; wrong = false }
        KnineSegmented(options = OwnRules.ACTIONS.map { actionName(it) }, selected = action, onSelect = { action = it }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            said?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
            TextButton(onClick = { said = onImport() }) { Text(stringResource(R.string.knine_routing_rule_import)) }
            TextButton(onClick = {
                val checked = OwnRules.value(KINDS[kind], value)
                if (checked == null || rules.size >= OwnRules.MOST) {
                    wrong = true
                } else {
                    onChange(OwnRules.clean(rules + Rule(KINDS[kind], checked, OwnRules.ACTIONS[action])))
                    value = ""
                }
            }) { Text(stringResource(R.string.knine_routing_rule_add)) }
        }
    }
}

/** The common category lists by group; the one picked fills in the rule's value. Any other SagerNet list can be typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(picked: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (group in listOf(Categories.SERVICES, Categories.WEBSITES, Categories.REGIONS)) {
            Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Categories.ALL.filter { it.group == group }.forEach { category ->
                    FilterChip(selected = category.name == picked, onClick = { onPick(category.name) }, label = { Text(category.title) })
                }
            }
        }
    }
}
