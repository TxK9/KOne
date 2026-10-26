package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.ktx.clipboardText
import cc.txwd.kone.shared.Categories
import cc.txwd.kone.shared.Countries
import cc.txwd.kone.shared.Hosts
import cc.txwd.kone.shared.ListStatus
import cc.txwd.kone.shared.NameServers
import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.RuleExport
import cc.txwd.kone.shared.Rules
import cc.txwd.kone.shared.Region
import io.nekohasekai.sfa.selfuse.CategoryListKeeper
import io.nekohasekai.sfa.selfuse.OwnRules
import io.nekohasekai.sfa.selfuse.PrivateDns
import io.nekohasekai.sfa.selfuse.Routing
import io.nekohasekai.sfa.selfuse.RoutingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // The countries the profiles' nodes say, which a rule can go through.
    var countries by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { countries = RoutingSettings.countries() }

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
        // The empty fields show the form a line takes; the labels say the rest.
        KnineRowGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = direct, onValueChange = { direct = it; saved = false },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_direct)) },
                    placeholder = { Text("example.cn") },
                )
                OutlinedTextField(
                    value = proxied, onValueChange = { proxied = it; saved = false },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_proxied)) },
                    placeholder = { Text("example.com") },
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
        KnineRowGroup {
            RulesEditor(choices.rules, lists, countries, onImport = {
                // Another client's rules, from the clipboard: Clash's rules: lines or v2rayN's JSON.
                val imported = Routing.importRules(choices, clipboardText.orEmpty())
                if (imported.choices != choices) keep(imported.choices)
                imported.said
            }) { keep(choices.copy(rules = it)) }
            // The category lists the rules name, downloaded again now if they changed (otherwise once a day).
            if (choices.rules.any { it.kind == "set" }) {
                var updatingLists by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !updatingLists, onClick = {
                        updatingLists = true
                        scope.launch {
                            CategoryListKeeper.update(force = true)
                            lists = withContext(Dispatchers.IO) { CategoryListKeeper.status() }
                            updatingLists = false
                        }
                    }) { Text(stringResource(if (updatingLists) R.string.knine_routing_lists_updating else R.string.knine_routing_lists_update)) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        // Three choices on their own, set apart by space alone: a heading over each only repeated it.
        KnineRowGroup {
            SettingsSwitchRow(stringResource(R.string.knine_routing_block_ads), choices.blockAds, { keep(choices.copy(blockAds = it)) })
            SettingsSwitchRow(stringResource(R.string.kone_settings_routing_automatic), choices.automatic, { keep(choices.copy(automatic = it)) })
            SettingsPickRow(
                title = stringResource(R.string.knine_routing_unmatched),
                options = listOf(stringResource(R.string.knine_routing_unmatched_proxy), stringResource(R.string.knine_routing_unmatched_direct)),
                selected = if (choices.unmatchedDirect) 1 else 0,
                onSelect = { if ((it == 1) != choices.unmatchedDirect) keep(choices.copy(unmatchedDirect = it == 1)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_dns))
        KnineRowGroup {
            val china = Region.china(choices.region)
            SettingsPickRow(
                title = stringResource(R.string.knine_routing_region),
                options = listOf(stringResource(R.string.knine_routing_region_global), stringResource(R.string.knine_routing_region_china)),
                selected = if (china) 1 else 0,
                onSelect = { picked ->
                    if ((picked == 1) != china) {
                        // A preset straight-out server follows the region to its default.
                        val region = if (picked == 1) Region.CHINA else Region.GLOBAL
                        keep(choices.copy(region = region, directDns = choices.directDns.takeUnless { it in Region.DIRECT }.orEmpty()))
                    }
                },
            )
            val remote = Routing.REMOTE.keys.indexOf(choices.remoteDns).coerceAtLeast(0)
            SettingsPickRow(
                title = stringResource(R.string.knine_routing_remote_dns),
                options = REMOTE_NAMES,
                selected = remote,
                onSelect = { if (it != remote) keep(choices.copy(remoteDns = if (it == 0) "" else Routing.REMOTE.keys.elementAt(it))) },
            )
            // The region's presets, its default first (kept as "" so it follows the region).
            val presets = Region.directPresets(choices.region).take(3)
            val direct = presets.indexOf(choices.directDns).coerceAtLeast(0)
            SettingsPickRow(
                title = stringResource(R.string.knine_routing_direct_dns),
                options = presets.map { DIRECT_NAMES.getValue(it) },
                selected = direct,
                onSelect = { if (it != direct) keep(choices.copy(directDns = if (it == 0) "" else presets[it])) },
            )
            SettingsSwitchRow(stringResource(R.string.knine_routing_ipv6), choices.ipv6, { keep(choices.copy(ipv6 = it)) })
            SettingsSwitchRow(stringResource(R.string.knine_routing_fake_ip), choices.fakeIp, { keep(choices.copy(fakeIp = it)) })
            // Android's Private DNS set to a provider answers apps past these servers: said where they are chosen.
            if (privateDns != null) {
                Text(
                    stringResource(R.string.kone_settings_routing_private_dns, privateDns.ifEmpty { stringResource(R.string.kone_settings_routing_private_dns_provider) }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        KnineSectionLabel(text = stringResource(R.string.knine_routing_names))
        KnineRowGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hosts, onValueChange = { hosts = it; namesSaved = null },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_hosts)) },
                    placeholder = { Text("nas.example.com 192.0.2.10") },
                )
                OutlinedTextField(
                    value = nameServers, onValueChange = { nameServers = it; namesSaved = null },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.knine_routing_name_servers)) },
                    placeholder = { Text("corp.example.com 192.0.2.53") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    namesSaved?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
                    TextButton(onClick = {
                        // Kept as the template takes them; a line that is not a name and an address is left out and said.
                        // A pasted hosts file is read as it is written: comments, several names a line, localhost passed over.
                        val readHosts = Hosts.read(hosts)
                        val serverLines = lines(nameServers)
                        val keptHosts = readHosts.hosts
                        val keptServers = serverLines.mapNotNull(NameServers::parse)
                        hosts = RoutingSettings.hostLines(keptHosts)
                        nameServers = RoutingSettings.nameServerLines(keptServers)
                        val left = readHosts.left + serverLines.size - keptServers.size
                        keep(choices.copy(hosts = keptHosts, nameServers = keptServers))
                        namesSaved = if (left > 0) context.getString(R.string.knine_routing_names_left, left) else context.getString(R.string.knine_routing_saved)
                    }) { Text(stringResource(R.string.knine_routing_save_names)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
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

/** Where a rule goes as its row says it: the node, straight out, blocked, or a country with its flag. */
@Composable
private fun actionName(action: String): String = Rules.via(action)?.let(::countryName) ?: stringResource(
    when (action) {
        "direct" -> R.string.knine_routing_action_direct
        "block" -> R.string.knine_routing_action_block
        else -> R.string.knine_routing_action_proxy
    },
)

private fun countryName(code: String): String = Countries.byCode(code)?.let { "${it.flag} ${it.label}" } ?: code

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
 * A rule on a category list says when its list is not on the phone yet ([lists]), and why; one
 * through a country, when no node there is among the profiles' ([countries]).
 */
@Composable
private fun RulesEditor(rules: List<Rule>, lists: Map<String, ListStatus>, countries: List<String>, onImport: () -> String, onChange: (List<Rule>) -> Unit) {
    val context = LocalContext.current
    var said by remember { mutableStateOf<String?>(null) }
    // The rule whose way is being changed, by its place.
    var changing by remember { mutableStateOf<Int?>(null) }
    var kind by remember { mutableStateOf(0) }
    var action by remember { mutableStateOf(0) }
    var country by remember(countries) { mutableStateOf(countries.firstOrNull()) }
    var value by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // The actions, then going through a country, last.
    val through = OwnRules.ACTIONS.size
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rules.isEmpty()) Text(stringResource(R.string.knine_routing_rules_none), style = MaterialTheme.typography.bodyMedium)
        rules.forEachIndexed { index, rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { changing = if (changing == index) null else index }) {
                    val shown = if (rule.kind == "set") Categories.title(rule.value) ?: rule.value else rule.value
                    Text("${kindName(rule.kind)} · $shown → ${actionName(rule.action)}", style = MaterialTheme.typography.bodyLarge)
                    val status = lists[rule.value]
                    if (rule.kind == "set" && status?.kept != true) {
                        val waiting = status?.error?.let { stringResource(R.string.knine_routing_list_failed, it) } ?: stringResource(R.string.knine_routing_list_waiting)
                        Text(waiting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (Rules.via(rule.action)?.let { it !in countries } == true) {
                        Text(stringResource(R.string.knine_routing_through_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = {
                    changing = null
                    if (index > 0) onChange(rules.toMutableList().also { it[index] = it[index - 1].also { _ -> it[index - 1] = rule } })
                }, enabled = index > 0) {
                    Icon(imageVector = Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.kone_settings_rule_up))
                }
                IconButton(onClick = { changing = null; onChange(rules.filterIndexed { i, _ -> i != index }) }) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = stringResource(R.string.kone_settings_rule_remove))
                }
            }
            AnimatedVisibility(changing == index, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                RuleWay(rule.action, countries) { way ->
                    changing = null
                    if (way != rule.action) onChange(OwnRules.clean(rules.toMutableList().also { it[index] = rule.copy(action = way) }))
                }
            }
        }
        // Chips rather than one segmented row: seven kinds never fit across a narrow phone.
        SettingsChips(options = KINDS.map { kindName(it) }, selected = kind, onSelect = { kind = it; wrong = false })
        OutlinedTextField(
            value = value, onValueChange = { value = it; wrong = false },
            modifier = Modifier.fillMaxWidth(), singleLine = true, isError = wrong,
            label = { Text(stringResource(R.string.knine_routing_rule_value)) },
            supportingText = { Text(if (wrong) stringResource(R.string.knine_routing_rule_wrong) else kindHint(KINDS[kind])) },
        )
        if (KINDS[kind] == "set") CategoryPicker(picked = value.trim().lowercase()) { value = it; wrong = false }
        SettingsChips(
            options = OwnRules.ACTIONS.map { actionName(it) } + stringResource(R.string.knine_routing_action_country),
            selected = action, onSelect = { action = it; wrong = false },
        )
        if (action == through) CountryPicker(countries, country) { country = it; wrong = false }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            said?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
            TextButton(onClick = { said = onImport() }) { Text(stringResource(R.string.knine_routing_rule_import)) }
            if (rules.isNotEmpty()) TextButton(onClick = {
                // As Clash rule lines, which either KOne, or another client, reads back.
                clipboardText = RuleExport.write(rules)
                said = context.resources.getQuantityString(R.plurals.knine_routing_rules_copied, rules.size, rules.size)
            }) { Text(stringResource(R.string.knine_routing_rule_copy)) }
            TextButton(onClick = {
                val checked = OwnRules.value(KINDS[kind], value)
                val goes = if (action == through) country?.let(Rules::through) else OwnRules.ACTIONS[action]
                if (checked == null || goes == null || rules.size >= OwnRules.MOST) {
                    wrong = true
                } else {
                    onChange(OwnRules.clean(rules + Rule(KINDS[kind], checked, goes)))
                    value = ""
                }
            }) { Text(stringResource(R.string.knine_routing_rule_add)) }
        }
    }
}

/**
 * Where a rule goes, changed where it stands: the node, straight out, blocked, or a country
 * ([countries], and the one it goes through now). [onPick] takes the new way.
 */
@Composable
private fun RuleWay(action: String, countries: List<String>, onPick: (String) -> Unit) {
    val now = Rules.via(action)
    var country by remember { mutableStateOf(now != null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsChips(
            options = OwnRules.ACTIONS.map { actionName(it) } + stringResource(R.string.knine_routing_action_country),
            selected = if (country) OwnRules.ACTIONS.size else OwnRules.ACTIONS.indexOf(action).coerceAtLeast(0),
            onSelect = { picked -> if (picked == OwnRules.ACTIONS.size) country = true else onPick(OwnRules.ACTIONS[picked]) },
        )
        if (country) CountryPicker((countries + listOfNotNull(now)).distinct(), now) { onPick(Rules.through(it)) }
    }
}

/** The countries the profiles' node names say, with their flags; the one picked is where the rule goes. */
@Composable
private fun CountryPicker(countries: List<String>, picked: String?, onPick: (String) -> Unit) {
    if (countries.isEmpty()) {
        Text(stringResource(R.string.knine_routing_through_no_countries), style = MaterialTheme.typography.bodyMedium)
        return
    }
    SettingsChips(options = countries.map(::countryName), selected = countries.indexOf(picked), onSelect = { onPick(countries[it]) })
}

/** The common category lists by group; the one picked fills in the rule's value. Any other SagerNet list can be typed. */
@Composable
private fun CategoryPicker(picked: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (group in listOf(Categories.SERVICES, Categories.WEBSITES, Categories.REGIONS)) {
            val inGroup = remember(group) { Categories.ALL.filter { it.group == group } }
            Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsChips(
                options = inGroup.map { it.title },
                selected = inGroup.indexOfFirst { it.name == picked },
                onSelect = { onPick(inGroup[it].name) },
            )
        }
    }
}
