package io.nekohasekai.sfa.compose.screen.log

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cc.txwd.kone.shared.Categories
import cc.txwd.kone.shared.Names
import cc.txwd.kone.shared.RuleProviders
import cc.txwd.kone.shared.Rules
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.connections.AppKind
import io.nekohasekai.sfa.compose.screen.connections.AppTraffic
import io.nekohasekai.sfa.compose.screen.connections.HostTraffic
import io.nekohasekai.sfa.compose.screen.connections.RouteKind
import io.nekohasekai.sfa.compose.screen.connections.RouteLabels
import io.nekohasekai.sfa.compose.screen.connections.RuleKind
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.selfuse.PairedProfile
import io.nekohasekai.sfa.selfuse.RoutingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/** How outbound tags read on the Status tab: KOne's own node tags as the node's name. */
@Immutable
internal data class RouteNames(val node: String?, val tags: Map<String, String>)

/** Apps going through KOne, busiest first; a tap unfolds an app into its hosts and the route each one takes. */
internal fun LazyListScope.statusConnections(
    apps: List<AppTraffic>,
    opened: Map<String, Long>,
    onToggle: (String) -> Unit,
    names: RouteNames,
    onClose: (List<String>) -> Unit,
) {
    if (apps.isEmpty()) return
    item(key = "apps") { Spacer(Modifier.height(18.dp)) }
    for (app in apps) {
        item(key = "app\u0000${app.key}", contentType = "app") {
            AppRow(app, open = app.key in opened, onToggle = { onToggle(app.key) }, modifier = Modifier.animateItem())
        }
        val openedAt = opened[app.key] ?: continue
        if (app.hosts.isEmpty()) continue
        items(app.hosts, key = { "host\u0000${app.key}\u0000${it.key}" }, contentType = { "host" }) { host ->
            Unfolding(openedAt, Modifier.animateItem()) { HostRow(host, names, onClose) }
        }
        if (app.hosts.size > 1) {
            item(key = "close\u0000${app.key}", contentType = "close") {
                Unfolding(openedAt, Modifier.animateItem()) { CloseAll { onClose(app.ids) } }
            }
        }
    }
}

/** Rows of an app that has just opened unfold in; rows scrolled back into view are simply there. */
@Composable
private fun Unfolding(openedAt: Long, modifier: Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(SystemClock.uptimeMillis() - openedAt > 450).apply { targetState = true } }
    AnimatedVisibility(state, modifier, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) { content() }
}

@Composable
private fun AppRow(app: AppTraffic, open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val installed = rememberInstalled(if (app.kind == AppKind.Package) app.name else null)
    val name = when (app.kind) {
        // Until the label is read the row stays nameless, rather than flashing the package name.
        AppKind.Package -> installed?.label ?: if (InstalledApps.known(app.name)) app.name else ""
        AppKind.Process -> app.name
        AppKind.System -> stringResource(R.string.kone_status_app_system)
        AppKind.Unknown -> stringResource(R.string.kone_status_app_unknown)
    }
    val ink = LocalContentColor.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.copy(alpha = if (open) 0.2f else 0.12f)))
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (app.hosts.isNotEmpty()) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(GLYPH), contentAlignment = Alignment.Center) {
                val icon = installed?.icon
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(GLYPH))
                } else {
                    Icon(
                        if (app.kind == AppKind.System) Icons.Default.Android else Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = ink.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    pluralStringResource(R.plurals.kone_status_connections, app.count, app.count),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.graphicsLayer { alpha = 0.7f },
                )
            }
            Spacer(Modifier.width(8.dp))
            Rate(app.download, app.upload, app.total)
        }
    }
}

@Composable
private fun HostRow(host: HostTraffic, names: RouteNames, onClose: (List<String>) -> Unit) {
    val ink = LocalContentColor.current
    val where = routeName(host, names)
    val why = ruleName(host.rule)
    val many = if (host.count > 1) pluralStringResource(R.plurals.kone_status_connections, host.count, host.count) else null
    val route = listOfNotNull(where, why, many).joinToString(" · ")
    // The other way this host can go from here (either, when it is blocked); Home's addresses stay as they are.
    val ways = remember(host.host, host.outbound, host.outboundType) {
        if (Names.site(host.host) == null && Names.network(host.host) == null) emptyList()
        else when (RouteLabels.kind(host.outbound, host.outboundType, PairedProfile.HOME_TAG)) {
            RouteKind.Direct -> listOf(false)
            RouteKind.Node -> listOf(true)
            RouteKind.Blocked -> listOf(true, false)
            RouteKind.Home -> emptyList()
        }
    }
    var acting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = GLYPH + 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).then(if (ways.isNotEmpty()) Modifier.clickable { acting = !acting } else Modifier)) {
                Text(host.host, style = MaterialTheme.typography.bodyMedium)
                Text(route, style = MaterialTheme.typography.labelSmall, modifier = Modifier.graphicsLayer { alpha = 0.7f })
            }
            Spacer(Modifier.width(8.dp))
            Rate(host.download, host.upload, host.total, compact = true)
            Box(Modifier.size(40.dp).clickable { onClose(host.ids) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.kone_status_close), modifier = Modifier.size(16.dp), tint = ink.copy(alpha = 0.5f))
            }
        }
        AnimatedVisibility(acting, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            HostWays(host.host, ways) { acting = false }
        }
    }
}

/**
 * Where a host goes, changed from its row: straight out or through the node, written as an own
 * rule first among them ([RoutingSettings.send]) and applied at once; the service reloads, and
 * the host's next connections show the new way. Said in place, then folded away.
 */
@Composable
private fun HostWays(host: String, ways: List<Boolean>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    Crossfade(sent, animationSpec = tween(240), label = "host way") { done ->
        Row(Modifier.fillMaxWidth().padding(start = GLYPH + 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            if (done) {
                Text(
                    stringResource(R.string.kone_routing_sent),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp).graphicsLayer { alpha = 0.75f },
                )
            } else {
                ways.forEach { direct ->
                    Text(
                        stringResource(if (direct) R.string.kone_routing_send_direct else R.string.kone_routing_send_proxy),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable(enabled = !sending) {
                                sending = true
                                scope.launch {
                                    if (RoutingSettings.send(host, direct)) {
                                        sent = true
                                        delay(1200)
                                    }
                                    sending = false
                                    sent = false
                                    onDone()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CloseAll(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
        Text(
            stringResource(R.string.kone_status_close_all),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp).graphicsLayer { alpha = 0.75f },
        )
    }
}

/** Live speeds while traffic moves; once idle, the amount carried so far, faint. Hosts speak a size below their app. */
@Composable
private fun Rate(download: Long, upload: Long, total: Long, compact: Boolean = false) {
    Column(Modifier.widthIn(min = if (compact) 52.dp else 64.dp), horizontalAlignment = Alignment.End) {
        if (download + upload > 0) {
            val lead = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
            Text("↓ " + stringResource(R.string.kone_status_speed, Libbox.formatBytes(download)), style = lead.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            Text("↑ " + stringResource(R.string.kone_status_speed, Libbox.formatBytes(upload)), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.graphicsLayer { alpha = 0.7f })
        } else {
            Text(Libbox.formatBytes(total), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.graphicsLayer { alpha = 0.55f })
        }
    }
}

@Composable
private fun routeName(host: HostTraffic, names: RouteNames): String = when (RouteLabels.kind(host.outbound, host.outboundType, PairedProfile.HOME_TAG)) {
    RouteKind.Direct -> stringResource(R.string.kone_status_route_direct)
    RouteKind.Blocked -> stringResource(R.string.kone_status_route_blocked)
    RouteKind.Home -> stringResource(R.string.kone_status_route_home)
    RouteKind.Node -> names.tags[host.outbound]
        ?: host.outbound.takeUnless { it.isBlank() || RouteLabels.looksLikeAddress(it) }
        ?: names.node
        ?: stringResource(R.string.kone_status_route_node)
}

@Composable
private fun ruleName(rule: String): String {
    val parsed = remember(rule) { RouteLabels.rule(rule) }
    val detail = parsed.detail
    val lists = remember(rule) { detail?.takeIf { parsed.kind == RuleKind.RuleSet }?.let(::listNames) }
    return when (parsed.kind) {
        RuleKind.Default -> stringResource(R.string.kone_status_rule_default)
        RuleKind.RuleSet -> lists ?: stringResource(R.string.kone_status_rule_other)
        RuleKind.Private -> stringResource(R.string.kone_status_rule_private)
        RuleKind.Domain -> stringResource(R.string.kone_status_rule_domain)
        RuleKind.Address -> stringResource(R.string.kone_status_rule_address)
        RuleKind.Port -> stringResource(R.string.kone_status_rule_port)
        RuleKind.App -> stringResource(R.string.kone_status_rule_app)
        RuleKind.Protocol -> detail?.let { stringResource(R.string.kone_status_rule_protocol, it) } ?: stringResource(R.string.kone_status_rule_other)
        RuleKind.Network -> detail?.let { stringResource(R.string.kone_status_rule_network, it.uppercase()) } ?: stringResource(R.string.kone_status_rule_other)
        RuleKind.Mode -> detail?.let { stringResource(R.string.kone_status_rule_mode, it.replaceFirstChar(Char::uppercaseChar)) } ?: stringResource(R.string.kone_status_rule_other)
        RuleKind.WiFi -> stringResource(R.string.kone_status_rule_wifi)
        RuleKind.Other -> stringResource(R.string.kone_status_rule_other)
    }
}

/**
 * The lists a rule names as people call them: a category list by its title (Netflix), an own list
 * by its file's name, found among the person's rules by its tag; any other as it is.
 */
private fun listNames(detail: String): String {
    val own by lazy { Rules.urls(RoutingSettings.current().rules).associateBy(RuleProviders::tag) }
    return detail.split(", ").joinToString(", ") { name ->
        Categories.title(name) ?: own[name]?.let(RuleProviders::title) ?: name
    }
}

private val GLYPH = 26.dp

private class Installed(val label: String, val icon: ImageBitmap?)

/** Labels and icons of installed apps, read once off the main thread and kept for the next second's rows. */
private object InstalledApps {
    private val missing = Installed("", null)
    private val cache = LruCache<String, Installed>(96)

    fun peek(packageName: String): Installed? = cache.get(packageName)?.takeUnless { it === missing }

    fun known(packageName: String): Boolean = cache.get(packageName) != null

    fun load(context: Context, packageName: String, iconPx: Int): Installed? {
        val loaded = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            Installed(info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull())
        } catch (_: PackageManager.NameNotFoundException) {
            missing
        }
        cache.put(packageName, loaded)
        return loaded.takeUnless { it === missing }
    }
}

@Composable
private fun rememberInstalled(packageName: String?): Installed? {
    if (packageName == null) return null
    val context = LocalContext.current.applicationContext
    val iconPx = with(LocalDensity.current) { GLYPH.roundToPx() }
    val installed by produceState(InstalledApps.peek(packageName), packageName) {
        if (!InstalledApps.known(packageName)) value = withContext(Dispatchers.IO) { InstalledApps.load(context, packageName, iconPx) }
    }
    return installed
}
