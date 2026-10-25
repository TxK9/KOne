package io.nekohasekai.sfa.compose.screen.dashboard

import cc.txwd.kone.shared.Countries
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sfa.compose.component.KnineConnectAction
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as BText
import io.nekohasekai.sfa.compose.theme.KnineAppearance
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.theme.KnineSwitcherStyle
import io.nekohasekai.sfa.compose.theme.LocalBackdrop
import io.nekohasekai.sfa.compose.theme.LocalBackdropHalo
import io.nekohasekai.sfa.compose.theme.LocalTransparentUi
import io.nekohasekai.sfa.compose.theme.knineSurface
import io.nekohasekai.sfa.compose.theme.rememberReadingAlpha
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.util.FlagGlyph
import io.nekohasekai.sfa.compose.util.FlagIcons
import io.nekohasekai.sfa.compose.util.ProfileIconGlyph
import io.nekohasekai.sfa.compose.util.ProfileIcons
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import io.nekohasekai.sfa.compose.theme.rememberBackdropBusyness
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Speed

/** The Tailscale home tunnel, reduced to what the dashboard needs to show. */
internal enum class HomeState {
    /** Not connected, or no home tunnel in this profile. */
    None,
    Online,

    /** Starting or syncing; it settles by itself. */
    Settling,

    /** Needs login, approval or a restart: worth saying in words. */
    NeedsAction,
}

/** What the dashboard borrows from the activity: the home tunnel state and its shared sheets. */
internal data class KnineHost(
    val homeStatus: String = "",
    val homeStatusAlpha: Float = 1f,
    val homeState: HomeState = HomeState.None,
    val openGroups: () -> Unit = {},
    val openConnections: () -> Unit = {},
    val openHome: () -> Unit = {},
)

internal val LocalKnineHost = compositionLocalOf { KnineHost() }

internal class KnineDashboardActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val select: (Long) -> Unit,
    val edit: (Profile) -> Unit,
    val selectMode: (String) -> Unit,
    val toggleSystemProxy: (Boolean) -> Unit,
    val import: () -> Unit = {},
    val delete: (Profile) -> Unit = {},
)

internal const val SHOWN_NODES = 3

internal fun routeModeLabel(mode: String): String = when (mode.lowercase()) {
    "rule" -> "Rule"
    "global" -> "Global"
    "direct" -> "Direct"
    else -> mode
}

private fun routeModeShort(mode: String): String = when (mode.lowercase()) {
    "rule" -> "Route by rules"
    "global" -> "Proxy all traffic"
    "direct" -> "Direct traffic"
    else -> ""
}

private fun routeModeHint(mode: String): String = when (mode.lowercase()) {
    "rule" -> "Choose routes using profile rules"
    "global" -> "Use the global proxy policy"
    "direct" -> "Connect directly without a proxy"
    else -> mode
}

/** The country a group stands for: its flag, or a name that is a country's name. */

/** Up to three nodes; the selected node is always among them. */
internal fun visibleNodes(group: NodeGroup, selectedId: Long): List<Profile> {
    if (group.profiles.size <= SHOWN_NODES) return group.profiles
    val top = group.profiles.take(SHOWN_NODES)
    val selected = group.profiles.firstOrNull { it.id == selectedId }
    return if (selected != null && selected !in top) top.take(SHOWN_NODES - 1) + selected else top
}

/**
 * The photo dashboard: one panel over the whole photograph, and the start action alone
 * in the bottom corner. The panel folds to a single line, so the photograph can show whole;
 * tapping that line lifts the nodes out of it. Nothing here opens a sheet: categories switch
 * on their tabs or the dial, edit in place, and the ways to import bloom out of the menu.
 */
@Composable
internal fun KnineDashboard(
    uiState: DashboardUiState,
    serviceStatus: Status,
    exit: ExitIpUi,
    actions: KnineDashboardActions,
    addCategoryRequested: Boolean = false,
    onAddCategoryShown: () -> Unit = {},
    imports: KnineImportActions? = null,
    importAnchor: Offset = Offset.Zero,
    onImportClosed: () -> Unit = {},
) {
    val host = LocalKnineHost.current
    val scaffold = LocalScaffoldPadding.current
    val topInset = scaffold.calculateTopPadding()
    val navInset = scaffold.calculateBottomPadding()
    val categoryState = NodeCategoryStore.state
    val profiles = uiState.profiles
    val groups = remember(profiles, categoryState) { NodeCategories.group(profiles, categoryState) }
    val selectedId = uiState.selectedProfileId
    val selectedGroupIndex = groups.indexOfFirst { group -> group.profiles.any { it.id == selectedId } }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedGroupIndex) { groups.size }
    val scope = rememberCoroutineScope()
    val started = serviceStatus == Status.Started

    var modeAnchor by remember { mutableStateOf<Offset?>(null) }
    // Where the import fan opens from when it was not the top bar's Import: the empty list's button.
    var importFrom by remember { mutableStateOf<Offset?>(null) }
    var dialAnchor by remember { mutableStateOf<Offset?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var shelfOpen by remember { mutableStateOf(false) }
    var statusOpen by rememberSaveable { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(Settings.kninePanelCollapsed) }
    fun closeShelf() {
        shelfOpen = false
        editing = null
    }
    fun setCollapsed(value: Boolean) {
        closeShelf()
        if (collapsed == value) return
        collapsed = value
        statusOpen = false
        Settings.kninePanelCollapsed = value
    }

    // A new category is named right where its nodes will be.
    fun addCategory() {
        val id = NodeCategoryStore.add("New category")
        if (collapsed) shelfOpen = true
        editing = id
    }
    LaunchedEffect(addCategoryRequested) {
        if (addCategoryRequested) {
            onAddCategoryShown()
            addCategory()
        }
    }
    // A node chosen elsewhere, such as a fresh import, brings its category into view.
    LaunchedEffect(selectedId) {
        if (editing == null && selectedGroupIndex != pagerState.currentPage) pagerState.scrollToPage(selectedGroupIndex)
    }
    BackHandler(enabled = editing != null || shelfOpen) {
        if (editing != null) editing = null else closeShelf()
    }

    // Connection details open on demand and fold away by themselves.
    LaunchedEffect(statusOpen) {
        if (statusOpen) {
            delay(8_000)
            statusOpen = false
        }
    }
    LaunchedEffect(started) { if (!started) statusOpen = false }

    // Node health, only while this page is on screen: every 6 s on Wi-Fi and mobile data and again whenever the connection changes the network underneath.
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(profiles, serviceStatus, selectedId, uiState.latencyRefreshToken) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                NodeLatency.refresh(context, profiles)
                delay(NodeLatency.gap() * 1_000)
            }
        }
    }
    // While connected, the core also tests the node in use through the proxy, every 6 s on
    // Wi-Fi and mobile data. A test that has not answered within 6 s
    // counts as a failure.
    val probeTime by rememberUpdatedState(uiState.probeTime)
    LaunchedEffect(started, selectedId, uiState.probeGroupTag, uiState.latencyRefreshToken) {
        val tag = uiState.probeGroupTag
        if (!started || tag == null) {
            NodeLatency.useProxyReadings(null)
            return@LaunchedEffect
        }
        NodeLatency.useProxyReadings(selectedId)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var pendingSince = 0L
            while (true) {
                val now = System.currentTimeMillis() / 1_000
                val answered = pendingSince == 0L || probeSeconds(probeTime) >= pendingSince - 1
                val overdue = !answered && now - pendingSince >= 6
                if (overdue) NodeLatency.recordProxy(selectedId, LatencySample(null, "Proxy unreachable"))
                if (overdue || answered && now - pendingSince >= NodeLatency.gap()) {
                    pendingSince = now
                    launch(Dispatchers.IO) { runCatching { CommandTarget.standaloneClient().urlTest(tag) } }
                }
                delay(NodeLatency.INTERVAL_MS)
            }
        }
    }
    // The painting behind shows the connected node's weather: mist when slow, fog when it does not answer.
    val selectedReading = NodeLatency[selectedId]
    SideEffect {
        io.nekohasekai.sfa.compose.theme.InkStage.weather.value = if (started) selectedReading?.let { NodeWeather.of(it.health) } else null
    }
    LaunchedEffect(uiState.probeTime, uiState.probeDelay) {
        if (started && uiState.probeTime > 0 && uiState.probeDelay > 0) {
            NodeLatency.recordProxy(selectedId, LatencySample(uiState.probeDelay))
        }
    }

    fun edit(profileId: Long) {
        closeShelf()
        profiles.firstOrNull { it.id == profileId }?.let(actions.edit)
    }

    // Picked from the folded line, the list sinks back once the check has shown.
    fun pick(profileId: Long) {
        actions.select(profileId)
        if (shelfOpen) {
            scope.launch {
                delay(320)
                closeShelf()
            }
        }
    }

    val overlay = modeAnchor != null || dialAnchor != null
    val panelAlpha by animateFloatAsState(if (overlay) 0f else 1f, tween(320), label = "panelAlpha")

    BackdropContrastScope(Modifier.fillMaxSize()) {
        val ink = LocalContentColor.current
        val surface = knineSurface()
        val backdrop = LocalBackdrop.current
        // Bright photos keep a glass card; dark photos drop it, like the rest of the app.
        val clear = LocalTransparentUi.current
        var panelBounds by remember { mutableStateOf(Rect.Zero) }
        var blockHeight by remember { mutableIntStateOf(0) }
        val support = if (backdrop != null) rememberReadingAlpha(backdrop, panelBounds, ink, 4.5f) else 0.9f
        val busy = if (backdrop != null) rememberBackdropBusyness(backdrop, panelBounds) else 1f
        // Thin glass over a calm photo, thicker over a busy one; the text glow carries the rest.
        val film = (support * lerp(0.45f, 0.85f, busy)).coerceIn(0.10f, 0.55f)
        val halo = surface.copy(alpha = if (clear) 0.92f else lerp(0.70f, 0.88f, busy))
        val density = LocalDensity.current
        // A photo whose subject sits low gets the panel above it instead of over it.
        val atTop = backdrop?.panelAtTop == true
        // Folded with its list lifted, the list is what the veil is measured for.
        val shelfShown = collapsed && shelfOpen
        val panelOnTop = atTop && !collapsed
        val measure = Modifier.onGloballyPositioned { panelBounds = it.boundsInWindow() }
        val glass = Modifier.knineFilm(RoundedCornerShape(28.dp), film, surface, ink.luminance() > 0.5f)
        val panelSurface = Modifier
            .then(if (shelfShown) Modifier else measure)
            // Folded, the line has no underlay at all, like the start action beside it.
            .then(if (clear || collapsed) Modifier else glass)
            .foldGesture(atTop && !collapsed) { setCollapsed(it) }
        // The lifted list never has a card: it floats over the veil on bright photos too.
        val shelfSurface = measure
        // Typing a category name lifts the bottom block above the keyboard.
        val bottomInset = maxOf(navInset, WindowInsets.ime.asPaddingValues().calculateBottomPadding())
        val edgeInset = if (panelOnTop) topInset else bottomInset
        val veilShown by animateFloatAsState(if ((clear && !collapsed) || shelfShown) 1f else 0f, tween(260), label = "veil")

        Box(Modifier.fillMaxSize()) {
            if (veilShown > 0f && blockHeight > 0) {
                // No card: a soft veil reaches in from the panel's edge just behind the text.
                // The folded line alone keeps only the text glow.
                val veil = support.coerceIn(0.28f, 0.8f)
                val veilHeight = with(density) { blockHeight.toDp() } + edgeInset + 102.dp
                val soft = (90f / veilHeight.value).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(if (panelOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(veilHeight)
                        .graphicsLayer { alpha = panelAlpha * veilShown }
                        .background(
                            if (panelOnTop) {
                                Brush.verticalGradient(
                                    0f to surface.copy(alpha = veil),
                                    (1f - soft) to surface.copy(alpha = veil * 0.8f),
                                    1f to Color.Transparent,
                                )
                            } else {
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    soft to surface.copy(alpha = veil * 0.8f),
                                    1f to surface.copy(alpha = veil),
                                )
                            },
                        ),
                )
            }
            CompositionLocalProvider(LocalBackdropHalo provides halo) {
                val deck: @Composable (Boolean, Modifier) -> Unit = { floating, modifier ->
                    NodeDeck(
                        groups = groups,
                        pagerState = pagerState,
                        selectedId = selectedId,
                        clear = clear,
                        editing = editing,
                        onEditingChange = { editing = it },
                        floating = floating,
                        onSelect = ::pick,
                        onEdit = ::edit,
                        onDelete = { id ->
                            profiles.firstOrNull { it.id == id }?.let { profile ->
                                if (id == selectedId && serviceStatus != Status.Stopped) {
                                    android.widget.Toast.makeText(context, "Disconnect before deleting the active node.", android.widget.Toast.LENGTH_SHORT).show()
                                } else actions.delete(profile)
                            }
                        },
                        onOpenDial = { dialAnchor = it },
                        modifier = modifier,
                        onImport = if (profiles.isEmpty()) ({ at -> importFrom = at; actions.import() }) else null,
                    )
                }
                val panel: @Composable (Modifier) -> Unit = { modifier ->
                    if (collapsed) {
                        CollapsedPanel(
                            uiState = uiState,
                            serviceStatus = serviceStatus,
                            exit = exit,
                            host = host,
                            onSwitch = { if (shelfOpen) closeShelf() else shelfOpen = true },
                            modifier = modifier,
                        )
                    } else {
                        ExpandedPanel(
                            uiState = uiState,
                            serviceStatus = serviceStatus,
                            exit = exit,
                            host = host,
                            clear = clear,
                            atTop = atTop,
                            statusOpen = statusOpen,
                            onToggleStatus = { statusOpen = !statusOpen },
                            actions = actions,
                            onOpenModes = { modeAnchor = it },
                            onJumpToSelected = { scope.launch { pagerState.animateScrollToPage(selectedGroupIndex, animationSpec = PAGE_GLIDE) } },
                            onCollapse = { setCollapsed(true) },
                            deck = { deck(false, Modifier.fillMaxWidth()) },
                            modifier = modifier,
                        )
                    }
                }
                // The original start action: no underlay, alone in the bottom corner.
                val startAction: @Composable () -> Unit = {
                    KnineConnectAction(serviceStatus, actions.start, actions.stop, glass = false)
                }
                if (panelOnTop) {
                    panel(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = 14.dp, end = 14.dp, top = topInset + 8.dp)
                            .fillMaxWidth()
                            .onGloballyPositioned { blockHeight = it.size.height }
                            .graphicsLayer { alpha = panelAlpha }
                            .then(panelSurface),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = navInset + 10.dp)
                            .graphicsLayer { alpha = panelAlpha },
                    ) { startAction() }
                } else {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 14.dp, end = 14.dp, bottom = bottomInset + 10.dp)
                            .fillMaxWidth()
                            .onGloballyPositioned { blockHeight = it.size.height }
                            .graphicsLayer { alpha = panelAlpha },
                    ) {
                        if (!collapsed) {
                            panel(Modifier.fillMaxWidth().then(panelSurface))
                            Spacer(Modifier.height(8.dp))
                        }
                        // Tapping the folded line lifts the nodes out of it, right above it.
                        AnimatedVisibility(
                            visible = shelfShown,
                            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Bottom) + fadeIn(),
                            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow), shrinkTowards = Alignment.Bottom) + fadeOut(),
                        ) {
                            Column {
                                deck(true, Modifier.fillMaxWidth().then(shelfSurface))
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        // Folded, the line and the start action share one horizontal line.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (collapsed) {
                                panel(Modifier.weight(1f).then(panelSurface))
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            startAction()
                        }
                    }
                }
            }

            dialAnchor?.let { anchor ->
                // Tapping the current category again: the categories fly out on the dial.
                KnineSwitcher(
                    style = KnineAppearance.switcher,
                    title = "Categories",
                    items = groups.map { SwitcherItem(it.name, it.profiles.size, it.flag) },
                    initial = pagerState.currentPage,
                    anchorInRoot = anchor,
                    bottomInset = navInset,
                    hint = { "${it + 1} / ${groups.size}" },
                    onSelect = { index ->
                        dialAnchor = null
                        if (index != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(index, animationSpec = PAGE_GLIDE) }
                    },
                    onDismiss = { dialAnchor = null },
                    onManage = { index -> groups.getOrNull(index)?.let { editing = it.id } },
                    onAdd = ::addCategory,
                )
            }
            modeAnchor?.let { anchor ->
                val modes = uiState.clashModes
                KnineSwitcher(
                    style = if (KnineAppearance.switcher == KnineSwitcherStyle.Cards) KnineSwitcherStyle.Floating else KnineAppearance.switcher,
                    title = "Routing mode",
                    items = modes.map { SwitcherItem(routeModeLabel(it)) },
                    initial = modes.indexOf(uiState.selectedClashMode).coerceAtLeast(0),
                    anchorInRoot = anchor,
                    bottomInset = navInset,
                    hint = { routeModeHint(modes[it]) },
                    onSelect = { index ->
                        modeAnchor = null
                        if (modes[index] != uiState.selectedClashMode) actions.selectMode(modes[index])
                    },
                    onDismiss = { modeAnchor = null },
                )
            }
            if (uiState.showAddProfileSheet && imports != null) {
                ImportBloom(
                    anchorInRoot = importFrom ?: importAnchor,
                    actions = imports,
                    onClosed = {
                        importFrom = null
                        onImportClosed()
                    },
                )
            }
        }
    }
}

/** The core reports URL test times in Unix seconds; accept milliseconds too. */
private fun probeSeconds(time: Long): Long = if (time > 100_000_000_000L) time / 1_000 else time

/** Dragging toward the panel's screen edge folds it to one line; dragging away unfolds it. */
private fun Modifier.foldGesture(atTop: Boolean, onFold: (Boolean) -> Unit): Modifier = pointerInput(atTop) {
    var travel = 0f
    val threshold = 40.dp.toPx()
    detectVerticalDragGestures(
        onDragStart = { travel = 0f },
        onDragEnd = {
            val towardEdge = if (atTop) -travel else travel
            if (towardEdge > threshold) onFold(true) else if (towardEdge < -threshold) onFold(false)
        },
    ) { change, amount ->
        travel += amount
        change.consume()
    }
}

/** A short bar on the folding edge; tapping it folds or unfolds the panel. */
@Composable
private fun FoldHandle(onTap: () -> Unit) {
    val ink = LocalContentColor.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp, 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ink.copy(alpha = 0.24f)),
        )
    }
}

@Composable
private fun ExpandedPanel(
    uiState: DashboardUiState,
    serviceStatus: Status,
    exit: ExitIpUi,
    host: KnineHost,
    clear: Boolean,
    atTop: Boolean,
    statusOpen: Boolean,
    onToggleStatus: () -> Unit,
    actions: KnineDashboardActions,
    onOpenModes: (Offset) -> Unit,
    onJumpToSelected: () -> Unit,
    onCollapse: () -> Unit,
    deck: @Composable () -> Unit,
    modifier: Modifier,
) {
    val ink = LocalContentColor.current
    val selectedId = uiState.selectedProfileId
    val selected = uiState.profiles.firstOrNull { it.id == selectedId }
    val started = serviceStatus == Status.Started
    Column(
        modifier.padding(start = 14.dp, end = 14.dp, top = if (atTop) 14.dp else 2.dp, bottom = if (atTop) 2.dp else 10.dp),
    ) {
        if (!atTop) FoldHandle(onCollapse)
        PanelHeader(
            uiState = uiState,
            serviceStatus = serviceStatus,
            exit = exit,
            host = host,
            name = selected?.name ?: "No node selected",
            open = statusOpen,
            onToggleStatus = onToggleStatus,
            onNameClick = onJumpToSelected,
        )
        AnimatedVisibility(
            visible = started && statusOpen,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            StatusDetails(
                uiState = uiState,
                exit = exit,
                reading = selected?.let { NodeLatency[it.id] },
                host = host,
                clear = clear,
                onModes = onOpenModes,
                onToggleProxy = actions.toggleSystemProxy,
            )
        }
        Box(
            Modifier
                .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
                .fillMaxWidth()
                .height(0.7.dp)
                .background(ink.copy(alpha = if (clear) 0.2f else 0.14f)),
        )
        deck()
        if (atTop) FoldHandle(onCollapse)
    }
}

/**
 * The folded panel: the flag, exit IP, health and home tunnel in the start action's own
 * type, on the start action's line. It wraps to a second line only when it must. Tapping it
 * lifts the nodes out of it; swiping up unfolds it. A failure is a red dot here; the reason waits in
 * the details.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CollapsedPanel(
    uiState: DashboardUiState,
    serviceStatus: Status,
    exit: ExitIpUi,
    host: KnineHost,
    onSwitch: () -> Unit,
    modifier: Modifier,
) {
    val started = serviceStatus == Status.Started
    val selected = uiState.profiles.firstOrNull { it.id == uiState.selectedProfileId }
    val reading = selected?.let { NodeLatency[it.id] }
    val flag = selected?.let { FlagIcons.codeFor(it.icon, it.name) }
    val type = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = true))
    // Every item is the same height, so wrapped or not they sit on one centre line.
    val item = Modifier.heightIn(min = 24.dp).padding(vertical = 2.dp)
    FlowRow(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSwitch)
            // The flag lines up with the node names lifted above it.
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(item, verticalAlignment = Alignment.CenterVertically) {
            if (flag != null) {
                FlagGlyph(flag, size = 18.dp)
            } else {
                BText(selected?.name ?: "No node selected", style = type, fontWeight = FontWeight.SemiBold, emphasized = true, maxLines = 2, softWrap = true, overflow = TextOverflow.Ellipsis)
            }
        }
        if (reading != null) {
            Row(item, verticalAlignment = Alignment.CenterVertically) { HealthLabel(reading, large = true) }
        }
        if (started && host.homeState != HomeState.None) {
            Row(item, verticalAlignment = Alignment.CenterVertically) { HomeMark(host) }
        }
    }
}

@Composable
private fun PanelHeader(
    uiState: DashboardUiState,
    serviceStatus: Status,
    exit: ExitIpUi,
    host: KnineHost,
    name: String,
    open: Boolean,
    onToggleStatus: () -> Unit,
    onNameClick: () -> Unit,
) {
    val started = serviceStatus == Status.Started
    val uptime = rememberUptime(if (started) uiState.serviceStartTime else null)
    val chevron by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "chevron")
    val lineStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.4.sp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Connecting never adds a row: the state stays on the first line, the exit joins the node line.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (started) Modifier.clickable(onClick = onToggleStatus) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(started = started, busy = serviceStatus == Status.Starting || serviceStatus == Status.Stopping)
            Spacer(Modifier.width(4.dp))
            BText(
                when (serviceStatus) {
                    Status.Started -> if (uptime.isEmpty()) "Connected" else "Connected · $uptime"
                    Status.Starting -> "Connecting"
                    Status.Stopping -> "Disconnecting"
                    Status.Stopped -> "Disconnected"
                },
                style = lineStyle,
                maxLines = 1,
                softWrap = false,
            )
            if (started) {
                HomeMark(host)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer {
                            rotationZ = chevron
                            alpha = 0.6f
                        },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNameClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BText(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.UnfoldMore, contentDescription = null, modifier = Modifier.size(16.dp).graphicsLayer { alpha = 0.7f })
            }
            if (started) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleStatus),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    FitText("↓ ${uiState.downlink}   ↑ ${uiState.uplink}", lineStyle, alignEnd = true)
                }
            }
        }
    }
}

/**
 * The home tunnel without a tile: a small house with a green dot when online, a faint house
 * while it settles, and words only when it needs the user.
 */
@Composable
internal fun HomeMark(host: KnineHost) {
    if (host.homeState == HomeState.None) return
    val ink = LocalContentColor.current
    val breath = if (host.homeState == HomeState.Settling) {
        val value by rememberInfiniteTransition(label = "homeSettling").animateFloat(
            .38f, .85f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "homeBreath")
        value
    } else 1f
    // The 44dp target is easy to tap; the status itself stays a small, quiet icon.
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = host.openHome), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Home, contentDescription = host.homeStatus.ifBlank { "Tailscale status" },
            modifier = Modifier.size(18.dp).graphicsLayer { alpha = breath })
        if (host.homeState == HomeState.Online || host.homeState == HomeState.NeedsAction) {
            val mark = if (host.homeState == HomeState.Online) Icons.Default.Check else Icons.Default.PriorityHigh
            Icon(mark, contentDescription = null, tint = if (host.homeState == HomeState.Online) okColor(ink) else healthColor(NodeHealth.Slow, ink),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 7.dp).size(10.dp))
        }
    }
}

/**
 * The weather and the latency: on the folded line and the node page the weather's seal, in the
 * lists its glyph alone. A failure shows the weather alone; its reason is in the details.
 */
@Composable
internal fun HealthLabel(reading: NodeReading, large: Boolean = false) {
    val ink = LocalContentColor.current
    val weather = NodeWeather.of(reading.health)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The seal carries the glyph itself now, so it stands alone rather than beside a second sun.
        if (large) WeatherSeal(weather, 18.dp) else WeatherGlyph(weather, 14.dp, ink)
        if (!reading.failed) {
            Spacer(Modifier.width(if (large) 6.dp else 4.dp))
            if (large) {
                BText(
                    reading.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    emphasized = true,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                BText(
                    reading.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.graphicsLayer { alpha = 0.72f },
                )
            }
        }
    }
}

internal fun healthColor(health: NodeHealth, ink: Color): Color = weatherInk(NodeWeather.of(health), ink)

@Composable
private fun rememberUptime(start: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (start != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    if (start == null) return ""
    val seconds = ((now - start) / 1_000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
}

private data class TileSpec(
    val label: String,
    val icon: ImageVector,
    val value: String,
    val sub: String,
    val onClick: ((Offset) -> Unit)? = null,
    val copy: String? = null,
)

/** Everything behind the status line, shown on demand. */
@Composable
private fun StatusDetails(
    uiState: DashboardUiState,
    exit: ExitIpUi,
    reading: NodeReading?,
    host: KnineHost,
    clear: Boolean,
    onModes: (Offset) -> Unit,
    onToggleProxy: (Boolean) -> Unit,
) {
    val ink = LocalContentColor.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val tiles = buildList {
        if (reading != null) {
            val verdict = when (reading.health) {
                NodeHealth.Good -> "Good"
                NodeHealth.Slow -> if (reading.failed) "No recent response" else "Slow"
                NodeHealth.Down -> "Repeatedly unresponsive"
            }
            add(TileSpec("Latency", Icons.Outlined.Speed, reading.label, verdict))
        }
        if (uiState.clashModeVisible && uiState.clashModes.isNotEmpty()) {
            add(TileSpec("Route", Icons.Outlined.Tune, routeModeLabel(uiState.selectedClashMode), routeModeShort(uiState.selectedClashMode), onClick = onModes))
        }
        if (uiState.trafficVisible) {
            add(TileSpec("Download", Icons.Default.ArrowDownward, uiState.downlink, "Total ${uiState.downlinkTotal}"))
            add(TileSpec("Upload", Icons.Default.ArrowUpward, uiState.uplink, "Total ${uiState.uplinkTotal}"))
        }
        add(TileSpec("Connection", Icons.Default.SwapVert, uiState.connectionsCount.toString(), "View connections", onClick = { host.openConnections() }))
        // Only worth a tile when some group actually offers a choice.
        if (uiState.choosableGroupsCount > 0) {
            add(TileSpec("Policy groups", Icons.Outlined.AccountTree, uiState.groupsCount.toString(), "View policy groups", onClick = { host.openGroups() }))
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (clear) Color.Transparent else ink.copy(alpha = 0.05f))
            .then(if (clear) Modifier.border(0.7.dp, ink.copy(alpha = 0.16f), shape) else Modifier)
            .padding(6.dp),
    ) {
        tiles.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.padding(top = if (rowIndex == 0) 0.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (clear) 0.dp else 6.dp),
            ) {
                row.forEachIndexed { columnIndex, tile ->
                    if (clear && columnIndex > 0) {
                        Box(Modifier.padding(vertical = 8.dp).width(0.7.dp).height(44.dp).background(ink.copy(alpha = 0.14f)))
                    }
                    StatusTile(
                        spec = tile,
                        order = rowIndex * 3 + columnIndex,
                        clear = clear,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (uiState.systemProxyVisible) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 2.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BText("System HTTP proxy", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                KnineSwitch(
                    checked = uiState.systemProxyEnabled,
                    onCheckedChange = onToggleProxy,
                    enabled = !uiState.systemProxySwitching,
                )
            }
        }
    }
}

@Composable
private fun StatusTile(spec: TileSpec, order: Int, clear: Boolean, onCopy: (String) -> Unit, modifier: Modifier) {
    val surface = knineSurface()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 50L)
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
    }
    var center by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * -14.dp.toPx()
                val s = 0.86f + 0.14f * p
                scaleX = s
                scaleY = s
            }
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(RoundedCornerShape(14.dp))
            .background(if (clear) Color.Transparent else surface.copy(alpha = 0.4f))
            .then(
                when {
                    spec.onClick != null -> Modifier.clickable { spec.onClick.invoke(center) }
                    // The whole tile copies, so its small icon never crowds the label.
                    spec.copy != null -> Modifier.clickable { onCopy(spec.copy) }
                    else -> Modifier
                },
            )
            .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(spec.icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            BText(spec.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            if (spec.copy != null) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(13.dp)
                        .graphicsLayer { alpha = 0.7f },
                )
            }
        }
        BText(
            if (spec.onClick != null && spec.label == "Route") "${spec.value} ⌄" else spec.value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
        if (spec.copy != null) {
            // A copyable value (the exit IP) is shown whole, never cut to an ellipsis.
            FitText(spec.sub, subStyle)
        } else {
            BText(spec.sub, style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One line whose font steps down until the whole text fits the width it is given. */
@Composable
private fun FitText(text: String, style: TextStyle, alignEnd: Boolean = false) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        val maxWidth = constraints.maxWidth
        val fitted = remember(text, style, maxWidth) {
            var current = style
            if (maxWidth != Constraints.Infinity && style.fontSize.isSp) {
                while (current.fontSize.value > 7f && measurer.measure(text, current, maxLines = 1, softWrap = false).size.width > maxWidth) {
                    current = current.copy(fontSize = (current.fontSize.value - 0.5f).sp)
                }
            }
            current
        }
        BText(text, style = fitted, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun NodeRow(
    profile: Profile,
    selected: Boolean,
    clear: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalContentColor.current
    val background by animateColorAsState(
        if (selected && !clear) ink.copy(alpha = 0.07f) else Color.Transparent,
        tween(300),
        label = "rowBg",
    )
    val reading = NodeLatency[profile.id]
    val flag = FlagIcons.codeFor(profile.icon, profile.name)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every row keeps the flag's place, so the names start on one line with or without one;
        // the selected row is marked by its weight and the check, never by shifting it.
        Box(Modifier.width(24.dp)) {
            when {
                flag != null -> FlagGlyph(flag, size = 18.dp)
                ProfileIcons.getIconById(profile.icon) != null ->
                    ProfileIconGlyph(profile.icon, fallback = Icons.Default.MoreHoriz, size = 18.dp, tint = ink.copy(alpha = 0.85f))
            }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            BText(
                profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (reading != null) {
                Spacer(Modifier.width(8.dp))
                HealthLabel(reading)
            }
        }
        AnimatedCheck(selected)
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreHoriz, contentDescription = "Edit node", modifier = Modifier.size(18.dp))
        }
    }
}
