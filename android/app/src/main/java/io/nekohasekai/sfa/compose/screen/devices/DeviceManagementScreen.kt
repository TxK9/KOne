package io.nekohasekai.sfa.compose.screen.devices

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.screen.dashboard.weatherInk
import io.nekohasekai.sfa.compose.screen.tools.FitLine
import io.nekohasekai.sfa.compose.screen.tools.TestGutter
import io.nekohasekai.sfa.compose.theme.KnineSegmented
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.devices.Availability
import io.nekohasekai.sfa.devices.DeviceResult
import io.nekohasekai.sfa.devices.DeviceRole
import io.nekohasekai.sfa.devices.EngineFeature
import io.nekohasekai.sfa.devices.EngineKind
import io.nekohasekai.sfa.devices.EngineLogLine
import io.nekohasekai.sfa.devices.LogLevel
import io.nekohasekai.sfa.devices.NodeGroup
import io.nekohasekai.sfa.devices.ProxyMode
import io.nekohasekai.sfa.devices.RoutingRule
import io.nekohasekai.sfa.devices.RunState
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/**
 * Tools -> Device management. The orrery above is the map; the surface below is the place. Choosing
 * an engine on the orbit turns it to the front and its controls take the device's place below, in
 * the same page; back, or the medallion, returns to the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceManagementScreen(navController: NavController, viewModel: DeviceManagementViewModel) {
    val overview by viewModel.overview.collectAsState()
    val selection by viewModel.selection.collectAsState()

    // Back from an engine turns the orbit home to the device before it leaves the page.
    BackHandler(enabled = selection != null) { viewModel.choose(null) }

    OverrideTopBar {
        TopAppBar(
            title = { Text("Device management") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                val kind = selection
                if (kind != null && overview.engines.getOrNull(kind.ordinal)?.availability == Availability.Ready) {
                    IconButton(onClick = { viewModel.refresh(kind) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
            },
        )
    }

    DeviceManagementContent(viewModel, Modifier.padding(LocalScaffoldPadding.current))
}

/** The page under the top bar: the orrery, the device's name, and the chosen surface. */
@Composable
internal fun DeviceManagementContent(viewModel: DeviceManagementViewModel, modifier: Modifier = Modifier) {
    val overview by viewModel.overview.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val discovery by viewModel.discovery.collectAsState()
    val problem by viewModel.deviceProblem.collectAsState()

    DevicePage(modifier) {
        // More than one device to manage: choose one above its orbit.
        if (devices.size > 1) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = TestGutter, vertical = 6.dp), contentAlignment = Alignment.Center) {
                KnineSegmented(
                    options = devices.map { if (it.role == DeviceRole.ThisDevice) "This device" else it.name },
                    selected = devices.indexOfFirst { it.id == overview.device.id },
                    onSelect = { viewModel.select(devices[it]) },
                )
            }
        }

        DeviceOrbit(overview = overview, selected = selection, onSelect = viewModel::choose)
        DeviceTitle(overview)

        AnimatedContent(
            targetState = selection,
            transitionSpec = {
                val from = initialState
                val to = targetState
                val enter: EnterTransition
                val exit: ExitTransition
                if (from == null || to == null) {
                    // Into an engine the surface rises a little; back to the device it settles down.
                    val rising = to != null
                    enter = fadeIn(tween(300, delayMillis = 90)) +
                        slideInVertically(tween(460, easing = FastOutSlowInEasing)) { h -> (if (rising) 1 else -1) * min(h, 640) / 10 }
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(240)) { h -> (if (rising) -1 else 1) * min(h, 640) / 18 }
                } else {
                    // The new engine's controls arrive from the side its body came from as the orbit turned.
                    val side = if (wrapDegrees(orbitSeat(to.ordinal) - orbitSeat(from.ordinal)) > 0f) -1 else 1
                    enter = fadeIn(tween(260, delayMillis = 80)) + slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { w -> side * w / 7 }
                    exit = fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { w -> -side * w / 10 }
                }
                (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.92f, stiffness = 360f) })
            },
            label = "surface",
        ) { kind ->
            if (kind == null) {
                DeviceSurface(overview, problem, discovery, devices.size)
            } else {
                EngineSurface(kind, viewModel, overview.device.name)
            }
        }
    }
}

/** The device's name under its medallion, and what it is and how it is linked, on one line. */
@Composable
private fun DeviceTitle(overview: DeviceOverviewState) {
    val (weather, word) = linkState(overview.integration, overview.link)
    Column(Modifier.fillMaxWidth().padding(horizontal = TestGutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(overview.device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val kind = if (overview.device.role == DeviceRole.ThisDevice) "This device" else overview.device.platform
            if (kind != null) {
                Text(kind, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.62f })
                Text("  ·  ", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.35f })
            }
            WeatherGlyph(weather, size = 15.dp)
            Spacer(Modifier.width(5.dp))
            Text(word, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.82f })
        }
    }
}

// ---- The device -------------------------------------------------------------------------------

@Composable
private fun DeviceSurface(overview: DeviceOverviewState, problem: DeviceResult<Nothing>?, discovery: Availability, deviceCount: Int) {
    val live = overview.integration == Availability.Ready
    Column(Modifier.fillMaxWidth()) {
        if (!live) {
            Spacer(Modifier.height(12.dp))
            Whisper("Device management activates once an integration is connected.")
        }
        problem?.let { ProblemLine(problemText(it), Modifier.padding(top = 8.dp)) }

        SectionRule("Status")
        val (linkWeather, linkWord) = linkState(overview.integration, overview.link)
        val (serviceWeather, serviceWord) = serviceSummary(overview)
        val configuration = overview.configuration
        val (configWeather, configWord) = when {
            overview.configurationAvailability != Availability.Ready -> NodeWeather.Rest to overview.configurationAvailability.word().orEmpty()
            configuration == null -> NodeWeather.Mist to "Waiting"
            else -> NodeWeather.Clear to (configuration.name ?: configuration.revision ?: Dash)
        }
        Stations(
            listOf(
                Station("Link", linkWord, linkWeather, live),
                Station("Service", serviceWord, serviceWeather, live),
                Station("Configuration", configWord, configWeather, live && overview.configurationAvailability == Availability.Ready),
            ),
        )

        SectionRule("Traffic", overview.traffic?.connections?.let { "$it active" })
        Waterline(overview.history, overview.traffic, TRAFFIC_SLOTS)
        if (live) overview.trafficAvailability.word()?.let { Whisper("Traffic · $it", Modifier.padding(top = 6.dp)) }

        SectionRule("Device")
        val info = overview.info
        SpecSheet(
            if (info == null) {
                listOf("Details" to (overview.integration.word() ?: Dash))
            } else {
                listOfNotNull(
                    info.model?.let { "Model" to it },
                    info.manufacturer?.let { "Maker" to it },
                    info.system?.let { "System" to it },
                    info.software?.let { "Software" to it },
                    info.architecture?.let { "Architecture" to it },
                ) + info.extra
            },
        )

        SectionRule("Other devices")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = TestGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ready = discovery == Availability.Ready
            Text("Find devices", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).graphicsLayer { alpha = if (ready) 1f else DimAlpha })
            if (!ready) {
                WeatherGlyph(NodeWeather.Rest, size = 15.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(discovery.word() ?: "${deviceCount - 1} found", style = MaterialTheme.typography.bodySmall, modifier = Modifier.graphicsLayer { alpha = 0.6f })
        }
    }
}

/** How the device's engines are doing, from what they reported. */
private fun serviceSummary(overview: DeviceOverviewState): Pair<NodeWeather, String> {
    overview.integration.word()?.let { return NodeWeather.Rest to it }
    val driven = overview.engines.filter { it.availability == Availability.Ready }
    if (driven.isEmpty()) return NodeWeather.Rest to "No engines"
    val reported = driven.mapNotNull { it.status }
    if (reported.isEmpty()) return NodeWeather.Mist to "Waiting"
    if (reported.any { it.state == RunState.Failed }) return NodeWeather.Cut to "Failed"
    val running = reported.count { it.state == RunState.Running }
    return when {
        running == 0 -> NodeWeather.Rest to "Stopped"
        running == driven.size -> NodeWeather.Clear to "Running"
        else -> NodeWeather.Mist to "$running of ${driven.size} running"
    }
}

// ---- An engine --------------------------------------------------------------------------------

@Composable
private fun EngineSurface(kind: EngineKind, viewModel: DeviceManagementViewModel, deviceName: String) {
    val page by remember(kind) { viewModel.engine(kind) }.collectAsState()
    // The open folds, as "nodes,logs": a plain string survives being saved.
    var folds by rememberSaveable(kind) { mutableStateOf("") }
    val open = folds.split(',').filter { it.isNotEmpty() }.toSet()
    fun toggle(fold: String) {
        folds = (if (fold in open) open - fold else open + fold).joinToString(",")
    }
    val ready = { feature: EngineFeature -> page.availability(feature) == Availability.Ready }
    val idle = !page.activity.busy
    val live = page.reach == Availability.Ready
    val state = page.status?.state

    Column(Modifier.fillMaxWidth()) {
        EngineHeading(kind, page)
        when (page.reach) {
            Availability.NotConnected -> Whisper("Controls activate once an integration for ${kind.title} is connected on $deviceName.", Modifier.padding(top = 14.dp))
            Availability.Unsupported -> Whisper("The integration for $deviceName does not manage ${kind.title}.", Modifier.padding(top = 14.dp))
            Availability.Ready -> page.status?.message?.let { Whisper(it, Modifier.padding(top = 14.dp)) }
        }
        page.activity.problem?.let { problem ->
            ProblemLine(problemText(problem), Modifier.padding(top = 8.dp).clickable { viewModel.dismissProblem(kind) })
        }

        SectionRule("Power")
        val canControl = ready(EngineFeature.Power) && idle
        Row(Modifier.fillMaxWidth().padding(horizontal = TestGutter), horizontalArrangement = Arrangement.SpaceEvenly) {
            InkStone(
                Icons.Outlined.PlayArrow, "Start",
                enabled = canControl && state != RunState.Running && state != RunState.Starting,
                emphasized = state == RunState.Stopped || state == RunState.Failed,
                onClick = { viewModel.start(kind) },
            )
            InkStone(
                Icons.Outlined.Stop, "Stop",
                enabled = canControl && (state == RunState.Running || state == RunState.Starting),
                emphasized = state == RunState.Running,
                onClick = { viewModel.stop(kind) },
            )
            InkStone(
                Icons.Outlined.RestartAlt, "Restart",
                enabled = canControl && state == RunState.Running,
                onClick = { viewModel.restart(kind) },
            )
        }
        Spacer(Modifier.height(8.dp))
        InkSwitchRow("Enabled", checked = page.status?.enabled, enabled = canControl) { viewModel.setEnabled(kind, it) }
        if (live) page.availability(EngineFeature.Power).word()?.let { Whisper("Start and stop · $it") }

        SectionRule("Proxy mode")
        val modes = ProxyMode.entries
        val canMode = ready(EngineFeature.Mode) && idle
        BrushSelector(
            options = modes.map { it.label },
            // No stroke until the engine has said which mode it is in.
            selected = page.mode?.let(modes::indexOf) ?: -1,
            onSelect = { viewModel.selectMode(kind, modes[it]) },
            optionEnabled = { canMode && modes[it] in page.supportedModes },
        )
        if (live) page.availability(EngineFeature.Mode).word()?.let { Whisper("Proxy mode · $it") }

        SectionRule("Traffic", page.traffic?.connections?.let { "$it active" })
        Waterline(page.history, page.traffic, TRAFFIC_SLOTS)
        if (live) page.availability(EngineFeature.Traffic).word()?.let { Whisper("Statistics · $it", Modifier.padding(top = 6.dp)) }

        Spacer(Modifier.height(22.dp))
        InkFold("Nodes", page.availability(EngineFeature.Nodes).word() ?: page.snapshot.groups?.let { "${it.size} groups" }, "nodes" in open, { toggle("nodes") }) {
            NodeGroups(
                groups = page.snapshot.groups.orEmpty(),
                enabled = ready(EngineFeature.Nodes) && idle,
                onSelect = { group, node -> viewModel.selectNode(kind, group, node) },
                onMeasure = { viewModel.measure(kind, it) },
            )
        }
        InkFold("Routing rules", page.availability(EngineFeature.Routing).word() ?: page.snapshot.routing?.let { "${it.rules.size} rules" }, "routing" in open, { toggle("routing") }) {
            RuleLines(page.snapshot.routing?.rules.orEmpty())
        }
        InkFold("Configuration", page.availability(EngineFeature.Configuration).word() ?: page.snapshot.configuration?.name, "config" in open, { toggle("config") }) {
            val summary = page.snapshot.configuration
            SpecSheet(
                listOf(
                    "Profile" to (summary?.name ?: Dash),
                    "Revision" to (summary?.revision ?: Dash),
                    "Format" to (summary?.format ?: Dash),
                    "Updated" to (summary?.updatedAt?.let(::time) ?: Dash),
                ),
            )
            QuietAction(Icons.Outlined.Refresh, "Reload", ready(EngineFeature.Configuration) && idle) { viewModel.refresh(kind) }
        }
        InkFold("Logs", page.availability(EngineFeature.Logs).word() ?: page.logs.takeIf { it.isNotEmpty() }?.let { "${it.size} lines" }, "logs" in open, { toggle("logs") }) {
            LogLines(page.logs)
        }
        // The last fold's own rule, so the column ends on a line like the others begin.
        val ink = LocalContentColor.current
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawBehind { drawLine(ink.copy(alpha = HairAlpha), Offset(TestGutter.toPx(), 0f), Offset(size.width - TestGutter.toPx(), 0f), strokeWidth = 1f) },
        )
    }
}

/** The engine's name and family on the left, its state on the right, sharing one baseline. */
@Composable
private fun EngineHeading(kind: EngineKind, page: EnginePageState) {
    val (weather, word) = engineState(page.reach, page.status)
    val status = page.status
    val since = status?.since?.takeIf { status.state == RunState.Running }?.let { "since " + clock(it) }
    Row(Modifier.fillMaxWidth().padding(start = TestGutter, end = TestGutter, top = 22.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            FitLine(kind.title, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(kind.family, page.status?.version, since).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.graphicsLayer { alpha = 0.6f },
            )
        }
        Row(Modifier.padding(start = 12.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(weather, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(word, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.82f })
        }
    }
}

/** Node groups: a choice of group when there are several, then its nodes with their measured delay. */
@Composable
private fun NodeGroups(groups: List<NodeGroup>, enabled: Boolean, onSelect: (String, String) -> Unit, onMeasure: (String) -> Unit) {
    if (groups.isEmpty()) {
        Whisper("No node groups", Modifier.padding(vertical = 10.dp))
        QuietAction(Icons.Outlined.Speed, "Test delay", false) {}
        return
    }
    var shown by rememberSaveable { mutableStateOf(groups.first().id) }
    if (groups.size > 1) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = TestGutter, vertical = 6.dp), contentAlignment = Alignment.Center) {
            KnineSegmented(
                options = groups.map { it.name },
                selected = groups.indexOfFirst { it.id == shown }.coerceAtLeast(0),
                onSelect = { shown = groups[it].id },
            )
        }
    }
    val group = groups.firstOrNull { it.id == shown } ?: groups.first()
    val ink = LocalContentColor.current
    val slowest = group.nodes.mapNotNull { it.latencyMs }.maxOrNull()?.coerceAtLeast(1) ?: 1
    group.nodes.forEach { node ->
        val chosen = node.id == group.selectedNodeId
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, InkBleed, enabled = enabled && group.selectable && !chosen, role = Role.RadioButton) {
                    onSelect(group.id, node.id)
                }
                .padding(horizontal = TestGutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (chosen) Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp))
            }
            // A long name wraps; it is never cut.
            Text(node.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 10.dp))
            node.protocol?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).graphicsLayer { alpha = 0.55f }) }
            // The delay as a short stroke in its weather's ink, then the number.
            val delay = node.latencyMs
            Box(
                Modifier.padding(start = 12.dp).width(28.dp).height(6.dp).drawBehind {
                    if (delay != null) {
                        val weather = if (delay < 300) NodeWeather.Clear else if (delay < 800) NodeWeather.Mist else NodeWeather.Cut
                        val length = size.width * (delay.toFloat() / slowest).coerceIn(0.12f, 1f)
                        drawLine(weatherInk(weather, ink), Offset(0f, size.height / 2), Offset(length, size.height / 2), strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                },
            )
            Text(delay?.let { "$it ms" } ?: Dash, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(start = 8.dp).graphicsLayer { alpha = 0.75f })
        }
    }
    QuietAction(Icons.Outlined.Speed, "Test delay", enabled) { onMeasure(group.id) }
}

@Composable
private fun RuleLines(rules: List<RoutingRule>) {
    if (rules.isEmpty()) {
        Whisper("No rules", Modifier.padding(vertical = 10.dp))
        return
    }
    val ink = LocalContentColor.current
    rules.take(RULES_SHOWN).forEachIndexed { index, rule ->
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { if (index > 0) drawLine(ink.copy(alpha = HairAlpha), Offset(TestGutter.toPx(), 0f), Offset(size.width - TestGutter.toPx(), 0f), strokeWidth = 1f) }
                .padding(horizontal = TestGutter, vertical = 11.dp)
                .graphicsLayer { alpha = if (rule.enabled) 1f else DimAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both sides wrap whole; the target keeps to at most two fifths of the line.
            Text(rule.match, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("  →  ", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 0.35f })
            Text(rule.target, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(0.4f).wrapContentWidth(Alignment.End))
        }
    }
    if (rules.size > RULES_SHOWN) Whisper("${rules.size - RULES_SHOWN} more", Modifier.padding(top = 6.dp))
}

@Composable
private fun LogLines(lines: List<EngineLogLine>) {
    if (lines.isEmpty()) {
        Whisper("No log lines", Modifier.padding(vertical = 10.dp))
        return
    }
    val ink = LocalContentColor.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TestGutter)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind { drawRect(ink.copy(alpha = 0.035f)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        lines.takeLast(LOG_SHOWN).forEach { line ->
            Text(
                listOfNotNull(line.time?.let(::clock), line.level.tag, line.message).joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp).graphicsLayer { alpha = if (line.level >= LogLevel.Warn) 1f else 0.72f },
            )
        }
    }
}

private val LogLevel.tag: String
    get() = when (this) {
        LogLevel.Trace -> "TRACE"
        LogLevel.Debug -> "DEBUG"
        LogLevel.Info -> "INFO"
        LogLevel.Warn -> "WARN"
        LogLevel.Error -> "ERROR"
    }

private fun time(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private fun clock(epochMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

private const val RULES_SHOWN = 50
private const val LOG_SHOWN = 100
