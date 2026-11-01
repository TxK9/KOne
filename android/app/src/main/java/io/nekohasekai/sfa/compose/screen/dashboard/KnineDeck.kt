package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import cc.txwd.kone.shared.Countries
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as BText
import io.nekohasekai.sfa.compose.theme.knineSurface
import io.nekohasekai.sfa.compose.util.FlagGlyph
import io.nekohasekai.sfa.compose.util.FlagIcons
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A node row's usual height, enough to tell when a list needs to scroll. */
private const val NODE_ROW_DP = 44

/** Two taps on the same flag within this long finish editing. */
private const val DOUBLE_TAP_MS = 400L

/** Switching categories glides at one even pace, whether tapped, swiped or picked on the dial. */
internal val PAGE_GLIDE = tween<Float>(durationMillis = 380, easing = FastOutSlowInEasing)

/**
 * Categories as a row of names over a pager of their nodes. Tapping another name or swiping
 * switches, tapping the current name again opens the dial, long-pressing a name edits that
 * category: the edit row unfolds under the names, which stay in view so a category can be
 * walked along many others, and tapping another name edits that one instead. The pager keeps
 * the height of its tallest page, so switching never crops a list. [floating] is the list
 * lifted out of the folded line: every node, drawn as type over the photograph, rising from
 * the bottom row.
 */
@Composable
internal fun NodeDeck(
    groups: List<NodeGroup>,
    pagerState: PagerState,
    selectedId: Long,
    clear: Boolean,
    editing: String?,
    onEditingChange: (String?) -> Unit,
    floating: Boolean,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onOpenDial: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    onImport: ((Offset) -> Unit)? = null,
    onDelete: ((Long) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The dashboard hands down new callbacks with every status tick; the pages get steady ones,
    // so a tick passes over the node rows instead of recomposing every one of them.
    val select = steady(onSelect)
    val edit = steady(onEdit)
    val delete = steady<Long> { onDelete?.invoke(it) }.takeIf { onDelete != null }
    val import = steady<Offset> { onImport?.invoke(it) }.takeIf { onImport != null }
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // With many nodes from several countries, a line of flags narrows the list to one country.
    var country by remember { mutableStateOf<String?>(null) }
    // A long category can be searched; a long press marks nodes to move or delete together.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var marks by remember { mutableStateOf<Set<Long>?>(null) }
    var moving by remember { mutableStateOf(false) }
    // Test all: the run, how far it has come, and how many it covers.
    var testing by remember { mutableStateOf<Job?>(null) }
    var tested by remember { mutableIntStateOf(0) }
    var testTotal by remember { mutableIntStateOf(0) }
    // Fastest first is worked out at set moments, so rows never jump while being read.
    var sortStamp by remember { mutableIntStateOf(0) }
    var toTop by remember { mutableIntStateOf(0) }
    val sort = NodeCategoryStore.sort
    fun endSelecting() {
        marks = null
        moving = false
    }
    LaunchedEffect(pagerState.currentPage) {
        country = null
        searching = false
        query = ""
        endSelecting()
        testing?.cancel()
        testing = null
    }
    LaunchedEffect(editing) { if (editing != null) endSelecting() }
    // Fastest first sorts again once the first check after it was chosen has come in.
    LaunchedEffect(sort) {
        if (sort != NodeSort.Latency) return@LaunchedEffect
        val start = NodeLatency.round
        snapshotFlow { NodeLatency.round }.first { it != start }
        sortStamp++
    }
    BackHandler(enabled = marks != null || searching) {
        if (marks != null) {
            endSelecting()
        } else {
            searching = false
            query = ""
        }
    }
    val sorted = remember(groups, sort, sortStamp) {
        // Read without watching, so a new latency does not reorder the list by itself.
        Snapshot.withoutReadObservation {
            groups.associate { group ->
                group.id to NodeListing.sort(group.profiles, sort, { it.name }) { NodeListing.rank(NodeLatency[it.id]) }
            }
        }
    }
    val current = groups.getOrNull(pagerState.currentPage)
    val currentSorted = current?.let { sorted[it.id] }.orEmpty()
    val filtering = country != null || query.isNotBlank()
    val currentShown = remember(currentSorted, country, query) {
        if (!filtering) {
            currentSorted
        } else {
            currentSorted.filter { profile ->
                val flag = FlagIcons.codeFor(profile.icon, profile.name)
                (country == null || flag == country) && NodeListing.matches(profile.name, flag, query)
            }
        }
    }
    // Copies of one node in this category, worked out off the main thread from the saved configs.
    val copies by produceState(emptySet<Long>(), current?.profiles, selectedId) {
        val profiles = current?.profiles.orEmpty()
        val managed = profiles.map { it.id }.filterTo(HashSet()) { io.nekohasekai.sfa.managed.ManagedState.isManaged(it) }
        value = withContext(Dispatchers.IO) {
            val prints = profiles.associate { it.id to NodeFingerprints.of(it) }
            NodeListing.duplicates(profiles.map { it.id }, { prints[it] }, selectedId) { it in managed }
        }
    }
    val all = floating || expanded
    val many = current != null && current.profiles.size > MANY_NODES
    val toolsShown = all && current != null && editing == null && (marks != null || many || copies.isNotEmpty() || searching)
    // The lifted list stops a third of the way up, so the city stays in view above even with many nodes.
    val listMax = (LocalConfiguration.current.screenHeightDp * if (floating) 0.34f else 0.42f).dp - if (toolsShown && floating) 44.dp else 0.dp
    fun startTest() {
        val nodes = currentShown
        testing?.cancel()
        testTotal = nodes.size
        tested = 0
        testing = scope.launch {
            NodeLatency.sweep(context, nodes) { tested = it }
            testing = null
            if (NodeCategoryStore.sort == NodeSort.Latency) {
                sortStamp++
                toTop++
            }
        }
    }
    // The last mark taken off ends selecting.
    fun toggleMark(id: Long) {
        marks = marks?.let { if (id in it) it - id else it + id }?.takeIf { it.isNotEmpty() }
    }
    fun markAll(ids: Collection<Long>) {
        marks = ids.toSet().takeIf { it.isNotEmpty() }
        if (!floating) expanded = true
    }

    // Every page is composed, so the tallest is known before anyone swipes to it.
    val heights = remember { mutableStateMapOf<String, Int>() }
    val tallest = groups.maxOfOrNull { heights[it.id] ?: 0 } ?: 0
    val height = remember { Animatable(0f) }
    LaunchedEffect(tallest) {
        if (tallest <= 0) return@LaunchedEffect
        if (height.value == 0f) {
            height.snapTo(tallest.toFloat())
        } else {
            height.animateTo(tallest.toFloat(), spring(dampingRatio = 0.9f, stiffness = 380f))
        }
    }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(620, easing = LinearEasing)) }

    // The category being edited stays in view, also after it moves; once gone, editing ends.
    LaunchedEffect(editing, groups) {
        if (editing == null) return@LaunchedEffect
        val index = groups.indexOfFirst { it.id == editing }
        if (index < 0) {
            onEditingChange(null)
        } else if (index != pagerState.currentPage) {
            pagerState.animateScrollToPage(index, animationSpec = PAGE_GLIDE)
        }
    }

    val editingGroup = editing?.let { id -> groups.firstOrNull { it.id == id } }
    // The edit row keeps its category while it folds away.
    var lastEditing by remember { mutableStateOf<NodeGroup?>(null) }
    if (editingGroup != null && editingGroup != lastEditing) SideEffect { lastEditing = editingGroup }

    Column(modifier) {
        // One category has nothing to switch to: its name would only repeat itself above the nodes.
        if (groups.size > 1 || editingGroup != null) CategoryTabs(
            groups = groups,
            pagerState = pagerState,
            editing = editingGroup?.id,
            onPick = { index ->
                if (editingGroup != null) {
                    groups.getOrNull(index)?.let { onEditingChange(it.id) }
                } else {
                    scope.launch { pagerState.animateScrollToPage(index, animationSpec = PAGE_GLIDE) }
                }
            },
            onOpenDial = onOpenDial,
            onLongPress = { onEditingChange(it) },
        )
        AnimatedVisibility(
            visible = editingGroup != null,
            enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) + fadeIn(tween(220, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) + fadeOut(tween(140)),
        ) {
            (editingGroup ?: lastEditing)?.let { group ->
                key(group.id) { CategoryEditor(group = group, onDone = { onEditingChange(null) }) }
            }
        }
        val flags = remember(current?.profiles) {
            current?.profiles.orEmpty().groupingBy { FlagIcons.codeFor(it.icon, it.name) }.eachCount()
        }
        AnimatedVisibility(
            visible = toolsShown,
            enter = io.nekohasekai.sfa.compose.theme.KnineMotion.reveal(),
            exit = io.nekohasekai.sfa.compose.theme.KnineMotion.conceal(),
        ) {
            val selection = marks
            AnimatedContent(
                targetState = selection != null,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
                label = "nodeTools",
            ) { selecting ->
                if (selecting && selection != null) {
                    val deletable = if (onDelete == null) 0 else selection.count { !io.nekohasekai.sfa.managed.ManagedState.isManaged(it) }
                    SelectionBar(
                        count = selection.size,
                        allMarked = currentShown.all { it.id in selection },
                        deletable = deletable,
                        moving = moving,
                        targets = groups.filter { it.id != current?.id },
                        onClose = ::endSelecting,
                        onMarkAll = { markAll(if (currentShown.all { it.id in selection }) emptySet() else currentShown.map { it.id }) },
                        onToggleMove = { moving = !moving },
                        onMove = { target ->
                            NodeCategoryStore.assignAll(selection, target.id)
                            endSelecting()
                        },
                        onDelete = {
                            selection.filterNot { io.nekohasekai.sfa.managed.ManagedState.isManaged(it) }.forEach { delete?.invoke(it) }
                            endSelecting()
                        },
                    )
                } else {
                    NodeTools(
                        searchable = searching || (current?.profiles?.size ?: 0) > LONG_NODES,
                        searching = searching,
                        query = query,
                        onQuery = { query = it },
                        onSearch = { open ->
                            searching = open
                            if (!open) query = ""
                        },
                        flags = if (many && flags.keys.count { it != null } >= 2) flags else emptyMap(),
                        country = country,
                        onCountry = { country = if (country == it) null else it },
                        copies = copies.size,
                        onCopies = { markAll(copies) },
                        sortable = many,
                        sort = sort,
                        onSort = {
                            NodeCategoryStore.sort = sort.next()
                            toTop++
                        },
                        progress = if (testing != null) tested.toFloat() / testTotal.coerceAtLeast(1) else null,
                        onTest = if (many) ({ if (testing == null) startTest() else { testing?.cancel(); testing = null } }) else null,
                    )
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .then(if (height.value > 0f) Modifier.height(with(density) { height.value.toDp() }) else Modifier),
            verticalAlignment = Alignment.Top,
            pageSpacing = 16.dp,
            beyondViewportPageCount = groups.size,
            userScrollEnabled = editing == null && marks == null,
            key = { groups.getOrNull(it)?.id ?: it },
        ) { index ->
            val group = groups.getOrNull(index) ?: return@HorizontalPager
            val isCurrent = index == pagerState.currentPage
            val filtered = group.copy(profiles = if (isCurrent) currentShown else sorted[group.id] ?: group.profiles)
            // Only a list too long for the screen scrolls; a short one leaves the swipe that folds the panel alone.
            val long = all && filtered.profiles.size * NODE_ROW_DP > listMax.value
            val list = rememberLazyListState()
            NodePage(
                group = filtered,
                selectedId = selectedId,
                clear = clear || floating,
                all = all,
                fromBottom = floating,
                rise = { rise.value },
                onToggleAll = if (floating) null else ({
                    expanded = !expanded
                    if (!expanded) {
                        endSelecting()
                        searching = false
                        query = ""
                    }
                }),
                onSelect = select,
                onEdit = edit,
                onDelete = delete,
                onImport = import,
                list = if (long) list else null,
                marks = if (isCurrent) marks else null,
                onMark = ::toggleMark,
                onLongPress = { id -> if (marks != null) toggleMark(id) else markAll(listOf(id)) },
                top = if (isCurrent) toTop else 0,
                filtering = isCurrent && filtering,
                modifier = Modifier
                    .naturalHeight { if (heights[group.id] != it) heights[group.id] = it }
                    .graphicsLayer {
                        // The page sliding away dims, the arriving one comes up to full.
                        val distance = abs(index - pagerState.currentPage - pagerState.currentPageOffsetFraction).coerceAtMost(1f)
                        alpha = 1f - distance * 0.6f
                    }
                    .then(if (long) Modifier.heightIn(max = listMax).fadingEdges(list) else Modifier),
            )
        }
    }
}

/** More nodes than this in one category bring out the tools above them: order, Test all, and flags from more than one country. */
private const val MANY_NODES = 8

/** More nodes than this in one category can be searched. */
private const val LONG_NODES = 16

/**
 * A scrolling list dissolves at the edge that has more beyond it, so rows ease into the city
 * rather than being cut off by a hard line; the fade grows with the first rows scrolled.
 */
private fun Modifier.fadingEdges(list: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = 36.dp.toPx()
            val top = 1f - (scrolledAbove(list) / edge).coerceIn(0f, 1f)
            val bottom = 1f - (scrolledBelow(list) / edge).coerceIn(0f, 1f)
            if (top < 1f) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = top), 1f to Color.Black, startY = 0f, endY = edge),
                    size = androidx.compose.ui.geometry.Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottom < 1f) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = bottom), startY = size.height - edge, endY = size.height),
                    topLeft = Offset(0f, size.height - edge),
                    size = androidx.compose.ui.geometry.Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/** How far the list has scrolled past its start, in px; a row's height and more once the first row is gone. */
private fun scrolledAbove(list: LazyListState): Float =
    if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE else list.firstVisibleItemScrollOffset.toFloat()

/** How much of the list lies below the view, in px; a row's height and more until the last row shows. */
private fun scrolledBelow(list: LazyListState): Float {
    val info = list.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
    return (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0).toFloat()
}

/** A callback that stays one object while always calling the latest [action]. */
@Composable
private fun <T> steady(action: (T) -> Unit): (T) -> Unit {
    val latest = rememberUpdatedState(action)
    return remember { { value: T -> latest.value(value) } }
}

/** The countries of a long list as flags with their counts; tapping one shows only its nodes, tapping it again shows all. */
@Composable
private fun CountryFilter(flags: Map<String?, Int>, picked: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        val countries = flags.entries.filter { it.key != null }.sortedByDescending { it.value }
        itemsIndexed(countries, key = { _, entry -> entry.key!! }) { _, (code, count) ->
            val chosen = code == picked
            val fill by animateFloatAsState(if (chosen) 1f else 0f, tween(220), label = "countryFill")
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(ink.copy(alpha = 0.08f + 0.14f * fill))
                    .border(1.dp, ink.copy(alpha = 0.5f * fill), RoundedCornerShape(16.dp))
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onPick(code!!) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlagGlyph(code!!, size = 16.dp)
                Spacer(Modifier.width(5.dp))
                BText(count.toString(), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
            }
        }
    }
}

/** Reports the content's own height, however little room the pager gives it. */
private fun Modifier.naturalHeight(onHeight: (Int) -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    onHeight(placeable.height)
    layout(placeable.width, minOf(placeable.height, constraints.maxHeight)) { placeable.place(0, 0) }
}

/** The category names in one line; a short rule under the current one follows the swipe. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryTabs(
    groups: List<NodeGroup>,
    pagerState: PagerState,
    editing: String?,
    onPick: (Int) -> Unit,
    onOpenDial: (Offset) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val ink = LocalContentColor.current
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    val editLabel = stringResource(R.string.kone_dashboard_edit_category)
    // Where each name sits in the row, start to end, without its touch padding.
    val spans = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    // Only read when tapped, so a plain map: the dial flies out of the tapped name.
    val centers = remember { HashMap<Int, Offset>() }
    var viewport by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage, viewport, spans.size) {
        val (start, end) = spans[pagerState.currentPage] ?: return@LaunchedEffect
        scroll.animateScrollTo(((start + end) / 2f - viewport / 2f).roundToInt().coerceIn(0, scroll.maxValue))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .onSizeChanged { viewport = it.width }
            // With many categories the row scrolls; the edge with more names beyond it fades out.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val start = if (scroll.value > 0) 0f else 1f
                val end = if (scroll.value < scroll.maxValue) 0f else 1f
                if (start < 1f || end < 1f) {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = start),
                            0.08f to Color.Black,
                            0.92f to Color.Black,
                            1f to Color.Black.copy(alpha = end),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .horizontalScroll(scroll)
            .drawBehind {
                val page = pagerState.currentPage
                val offset = pagerState.currentPageOffsetFraction
                val from = spans[page] ?: return@drawBehind
                val to = spans[if (offset >= 0f) page + 1 else page - 1] ?: from
                val f = abs(offset)
                val y = size.height - 4.dp.toPx()
                drawLine(
                    ink,
                    Offset(lerp(from.first, to.first, f), y),
                    Offset(lerp(from.second, to.second, f), y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEachIndexed { index, group ->
            val current = index == pagerState.currentPage
            // The category being edited wears a ring among the others.
            val ring by animateFloatAsState(if (group.id == editing) 1f else 0f, tween(200), label = "tabRing")
            val tabShape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .onGloballyPositioned { centers[index] = it.boundsInRoot().center }
                    .clip(tabShape)
                    .background(ink.copy(alpha = 0.1f * ring), tabShape)
                    .border(1.dp, ink.copy(alpha = 0.4f * ring), tabShape)
                    .semantics { selected = index == pagerState.currentPage }
                    .combinedClickable(
                        role = Role.Tab,
                        onLongClickLabel = editLabel,
                        onClick = {
                            if (editing == null && index == pagerState.currentPage && !pagerState.isScrollInProgress) {
                                onOpenDial(centers[index] ?: Offset.Zero)
                            } else {
                                onPick(index)
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(group.id)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .onGloballyPositioned {
                        val x = it.positionInParent().x
                        spans[index] = x to x + it.size.width
                    }
                    .graphicsLayer {
                        // Brightens as it slides under the rule, rather than switching at the halfway mark.
                        val distance = abs(index - pagerState.currentPage - pagerState.currentPageOffsetFraction).coerceAtMost(1f)
                        alpha = 1f - distance * 0.5f
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                group.flag?.let {
                    FlagGlyph(it, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                }
                BText(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
                if (group.profiles.isNotEmpty()) {
                    BText(
                        group.profiles.size.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier
                            .align(Alignment.Top)
                            .padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The long-pressed name turned editable: the name in a pill, saved as it is typed; order,
 * delete and a solid done button beside it; flags in a line below. A flag is chosen with one
 * tap; tapping it again, or the done button, finishes.
 */
@Composable
private fun CategoryEditor(group: NodeGroup, onDone: () -> Unit) {
    val context = LocalContext.current
    val ink = LocalContentColor.current
    val surface = knineSurface()
    val focusManager = LocalFocusManager.current
    val categories = NodeCategoryStore.state.categories
    val index = categories.indexOfFirst { it.id == group.id }
    var text by remember(group.id) { mutableStateOf(TextFieldValue(group.name, TextRange(group.name.length))) }
    fun finish() {
        focusManager.clearFocus()
        onDone()
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pill = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(pill)
                    .background(ink.copy(alpha = 0.08f))
                    .border(0.7.dp, ink.copy(alpha = 0.2f), pill)
                    .padding(start = 11.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = group.flag,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = 0.5f, stiffness = 520f), initialScale = 0.3f) + fadeIn(tween(120)))
                            .togetherWith(scaleOut(tween(120), targetScale = 0.3f) + fadeOut(tween(120)))
                    },
                    label = "editorFlag",
                ) { flag ->
                    if (flag != null) {
                        FlagGlyph(flag, size = 18.dp)
                    } else {
                        Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(16.dp).graphicsLayer { alpha = 0.55f })
                    }
                }
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { value ->
                        // An empty name is never saved; the old one stays until a new one is typed.
                        text = value.copy(text = value.text.replace('\n', ' ').take(NodeCategories.MAX_NAME))
                        NodeCategoryStore.rename(group.id, text.text)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleSmall.copy(color = ink, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(4.dp))
            if (!group.fallback) {
                // Read the live position on every step: holding an arrow moves it again and again.
                fun step(by: Int) {
                    val list = NodeCategoryStore.state.categories
                    val at = list.indexOfFirst { it.id == group.id }
                    if (at >= 0 && at + by in list.indices) NodeCategoryStore.move(group.id, at + by)
                }
                if (index > 0) {
                    RepeatingAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move up") { step(-1) }
                }
                if (index in 0 until categories.lastIndex) {
                    RepeatingAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move down") { step(1) }
                }
                EditorAction(Icons.Outlined.DeleteOutline, "Delete category") {
                    NodeCategoryStore.delete(group.id)
                    Toast.makeText(context, "Deleted ${group.name}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ink)
                    .clickable(role = Role.Button, onClick = ::finish),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = stringResource(R.string.kone_dashboard_done), tint = surface, modifier = Modifier.size(18.dp))
            }
        }
        FlagStrip(
            current = group.flag,
            onPick = { code ->
                NodeCategoryStore.setFlag(group.id, code)
                // A fresh category takes the country's name with its flag.
                val country = Countries.byCode(code)
                if (country != null && !group.fallback && group.name == "New category") {
                    NodeCategoryStore.rename(group.id, country.label)
                    text = TextFieldValue(country.label, TextRange(country.label.length))
                }
            },
            onFinish = ::finish,
        )
    }
}

/** Acts once on touch, then keeps acting while held, for walking past many categories. */
@Composable
private fun RepeatingAction(icon: ImageVector, label: String, onStep: () -> Unit) {
    val ink = LocalContentColor.current
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "repeatPress")
    Box(
        Modifier
            .size(34.dp)
            .graphicsLayer {
                val s = 1f - 0.08f * press
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.1f * press))
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    step()
                    val repeat = scope.launch {
                        delay(420)
                        while (true) {
                            step()
                            delay(160)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun EditorAction(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val lit by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "actionOn")
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.1f * lit))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(19.dp))
    }
}

/**
 * Flags in one line that fades at both ends; they pop in one after another. A tap picks a
 * flag, a second tap on it finishes. [onMore] adds a last circle that opens every icon.
 */
@Composable
internal fun FlagStrip(
    current: String?,
    onPick: (String?) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
) {
    val state = rememberLazyListState()
    val first = remember { (Countries.all.indexOfFirst { it.code == current } + 1 - 2).coerceAtLeast(0) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (first > 0) state.scrollToItem(first)
        appear.animateTo(1f, tween(560, easing = LinearEasing))
    }
    var lastTap by remember { mutableStateOf<Pair<String?, Long>?>(null) }
    fun tap(code: String?) {
        val now = SystemClock.uptimeMillis()
        val previous = lastTap
        lastTap = code to now
        if (previous != null && previous.first == code && now - previous.second < DOUBLE_TAP_MS) onFinish() else onPick(code)
    }
    val choices: List<String?> = listOf(null) + Countries.all.map { it.code }
    LazyRow(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.06f to Color.Black, 0.94f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(choices, key = { _, code -> code ?: "none" }) { i, code ->
            val order = (i - first).coerceIn(0, 10)
            FlagChoice(
                code = code,
                selected = code == current,
                appear = { (((appear.value - order * 0.045f) / 0.55f).coerceIn(0f, 1f)) },
                onTap = { tap(code) },
            )
        }
        if (onMore != null) {
            item(key = "more") {
                val ink = LocalContentColor.current
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .border(0.8.dp, ink.copy(alpha = 0.3f), CircleShape)
                        .clickable(role = Role.Button, onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = stringResource(R.string.kone_dashboard_more_icons), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FlagChoice(code: String?, selected: Boolean, appear: () -> Float, onTap: () -> Unit) {
    val ink = LocalContentColor.current
    val name = if (code == null) stringResource(R.string.kone_dashboard_no_flag) else flagName(code)
    val chosen by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "flagChosen")
    Box(
        Modifier
            .size(42.dp)
            .graphicsLayer {
                val p = appear()
                val s = (0.4f + 0.6f * backOut(p)) * (1f + 0.1f * chosen)
                scaleX = s
                scaleY = s
                alpha = p
            }
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.1f * chosen.coerceIn(0f, 1f)))
            .border(1.3.dp, ink.copy(alpha = 0.8f * chosen.coerceIn(0f, 1f)), CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onTap)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (code != null) {
            FlagGlyph(code, size = 22.dp)
        } else {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp).graphicsLayer { alpha = 0.6f })
        }
    }
}

@Composable
private fun NodePage(
    group: NodeGroup,
    selectedId: Long,
    clear: Boolean,
    all: Boolean,
    fromBottom: Boolean,
    rise: () -> Float,
    onToggleAll: (() -> Unit)?,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: ((Long) -> Unit)?,
    onImport: ((Offset) -> Unit)?,
    modifier: Modifier,
    list: LazyListState? = null,
    marks: Set<Long>? = null,
    onMark: (Long) -> Unit = {},
    onLongPress: (Long) -> Unit = {},
    top: Int = 0,
    filtering: Boolean = false,
) {
    // A new order starts from its first row.
    if (list != null) LaunchedEffect(top) { if (top > 0) list.animateScrollToItem(0) }
    if (group.profiles.isEmpty()) {
        // A search that finds nothing leaves the list empty; the field above says why.
        if (filtering) {
            Spacer(modifier.fillMaxWidth().height(NODE_ROW_DP.dp))
            return
        }
        Column(modifier.fillMaxWidth()) {
            if (onImport != null) {
                // Nothing here yet at all: the one thing to do is to bring a node in.
                val ink = LocalContentColor.current
                var center by remember { mutableStateOf(Offset.Zero) }
                Row(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .onGloballyPositioned { center = it.boundsInRoot().center }
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, ink.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .clickable { onImport(center) }
                        .padding(start = 14.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    BText("Import nodes", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            } else {
                BText(
                    "No nodes yet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                        .graphicsLayer { alpha = 0.7f },
                )
            }
        }
        return
    }
    val shown = if (all) group.profiles else visibleNodes(group, selectedId)
    val toggle = onToggleAll != null && foldsNodes(group)
    // One row after another; the lifted list starts from the row nearest the folded line.
    fun order(i: Int) = (if (fromBottom) shown.size - 1 - i else i).coerceAtMost(8)
    if (list != null) {
        // A list longer than the screen composes only the rows in view, however many nodes it holds.
        LazyColumn(modifier.fillMaxWidth(), state = list) {
            itemsIndexed(shown, key = { _, profile -> profile.id }) { i, profile ->
                // A new order or a filter moves the rows to their places rather than redrawing them there.
                NodeItem(profile, order(i), selectedId, clear, rise, onSelect, onEdit, onDelete, marks?.contains(profile.id), onMark, onLongPress, Modifier.animateItem())
            }
            if (toggle) item(key = "all") { AllNodesToggle(group, all, onToggleAll) }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            shown.forEachIndexed { i, profile ->
                key(profile.id) { NodeItem(profile, order(i), selectedId, clear, rise, onSelect, onEdit, onDelete, marks?.contains(profile.id), onMark, onLongPress) }
            }
            if (toggle) AllNodesToggle(group, all, onToggleAll)
        }
    }
}

@Composable
private fun NodeItem(
    profile: Profile,
    order: Int,
    selectedId: Long,
    clear: Boolean,
    rise: () -> Float,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: ((Long) -> Unit)?,
    marked: Boolean? = null,
    onMark: (Long) -> Unit = {},
    onLongPress: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val shareContext = LocalContext.current
    val shareScope = rememberCoroutineScope()
    // A long list checks by itself only the rows in view.
    DisposableEffect(profile.id) {
        NodeLatency.show(profile.id)
        onDispose { NodeLatency.hide(profile.id) }
    }
    SwipeNode(
        deletable = onDelete != null && !io.nekohasekai.sfa.managed.ManagedState.isManaged(profile.id),
        onDelete = { onDelete?.invoke(profile.id) },
        onShare = if (io.nekohasekai.sfa.compose.component.canShareNode(profile)) ({
            shareScope.launch { io.nekohasekai.sfa.compose.component.shareNodeAsText(shareContext, profile) }
        }) else null,
        modifier = modifier,
        enabled = marked == null,
    ) {
        NodeRow(
            profile = profile,
            selected = profile.id == selectedId,
            clear = clear,
            onSelect = { if (marked != null) onMark(profile.id) else onSelect(profile.id) },
            onEdit = { onEdit(profile.id) },
            marked = marked,
            onLongPress = { onLongPress(profile.id) },
            modifier = Modifier.graphicsLayer {
                val p = FastOutSlowInEasing.transform(((rise() - order * 0.07f) / 0.5f).coerceIn(0f, 1f))
                alpha = p
                translationY = (1f - p) * 18.dp.toPx()
            },
        )
    }
}

/** Under a folded page: show every node, or fold them back. */
@Composable
private fun AllNodesToggle(group: NodeGroup, all: Boolean, onToggleAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggleAll)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BText(if (all) stringResource(R.string.kone_dashboard_collapse) else pluralStringResource(R.plurals.kone_dashboard_all_nodes, group.profiles.size, group.profiles.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(if (all) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** A flag's country in the phone's language, for screen readers; the code when Android has no name for it. */
private fun flagName(code: String): String =
    java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: Countries.byCode(code)?.en ?: code

/** The longest search kept. */
private const val MAX_QUERY = 40

/**
 * The line over a long category: search, the countries in it, copies of one node, the order and
 * Test all. Each shows only when it has something to do; search takes the whole line while open.
 */
@Composable
private fun NodeTools(
    searchable: Boolean,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: (Boolean) -> Unit,
    flags: Map<String?, Int>,
    country: String?,
    onCountry: (String) -> Unit,
    copies: Int,
    onCopies: () -> Unit,
    sortable: Boolean,
    sort: NodeSort,
    onSort: () -> Unit,
    progress: Float?,
    onTest: (() -> Unit)?,
) {
    AnimatedContent(
        targetState = searching,
        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
        label = "nodeSearch",
    ) { open ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (open) {
                SearchField(query, onQuery, onClose = { onSearch(false) }, modifier = Modifier.weight(1f))
            } else {
                if (searchable) EditorAction(Icons.Outlined.Search, stringResource(R.string.kone_nodes_search)) { onSearch(true) }
                if (flags.isNotEmpty()) {
                    CountryFilter(flags = flags, picked = country, onPick = onCountry, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (copies > 0) CopiesChip(copies, onCopies)
                if (sortable) SortButton(sort, onSort)
                if (onTest != null) TestButton(progress, onTest)
            }
        }
    }
}

/** The search, typed into a pill like a category's name; closing it shows every node again. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val label = stringResource(R.string.kone_nodes_search)
    val pill = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(36.dp)
            .clip(pill)
            .background(ink.copy(alpha = 0.08f))
            .border(0.7.dp, ink.copy(alpha = 0.2f), pill)
            .padding(start = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp).graphicsLayer { alpha = 0.7f })
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = { onQuery(it.replace('\n', ' ').take(MAX_QUERY)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .semantics { contentDescription = label },
        )
        EditorAction(Icons.Default.Close, stringResource(R.string.kone_nodes_close_search), onClick = onClose)
    }
}

/** Copies of one node in this category, counted; tapping marks them, ready to delete. */
@Composable
private fun CopiesChip(count: Int, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val label = pluralStringResource(R.plurals.kone_nodes_copies, count, count)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(shape)
            .border(0.8.dp, ink.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FilterNone, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        BText(count.toString(), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
    }
}

/** The order, as saved, fastest first or by name; each tap moves to the next and the rows glide there. */
@Composable
private fun SortButton(sort: NodeSort, onSort: () -> Unit) {
    val ink = LocalContentColor.current
    fun labelOf(of: NodeSort) = when (of) {
        NodeSort.Saved -> R.string.kone_nodes_sort_saved
        NodeSort.Latency -> R.string.kone_nodes_sort_latency
        NodeSort.Name -> R.string.kone_nodes_sort_name
    }
    val label = stringResource(labelOf(sort))
    val next = stringResource(labelOf(sort.next()))
    // A chosen order stays lit, so a list out of its saved order says so.
    val lit by animateFloatAsState(if (sort == NodeSort.Saved) 0f else 1f, tween(200), label = "sortLit")
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.1f * lit))
            .clickable(role = Role.Button, onClickLabel = next, onClick = onSort)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = sort,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.7f)) togetherWith
                    (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.7f))
            },
            label = "sortIcon",
        ) { shown ->
            val icon = when (shown) {
                NodeSort.Saved -> Icons.AutoMirrored.Outlined.Sort
                NodeSort.Latency -> Icons.Outlined.Speed
                NodeSort.Name -> Icons.Outlined.SortByAlpha
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        }
    }
}

/** Test all: a bolt at rest; while testing, a ring filling as results come in, which stops the test when tapped. */
@Composable
private fun TestButton(progress: Float?, onTap: () -> Unit) {
    val ink = LocalContentColor.current
    val label = stringResource(if (progress == null) R.string.kone_nodes_test else R.string.kone_nodes_stop_test)
    val filled by animateFloatAsState(progress ?: 0f, tween(240), label = "testProgress")
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = progress != null,
            transitionSpec = { fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
            label = "testState",
        ) { running ->
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                if (running) {
                    Canvas(Modifier.size(22.dp)) {
                        val stroke = 2.dp.toPx()
                        val inset = stroke / 2
                        val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                        drawCircle(ink.copy(alpha = 0.18f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                        drawArc(ink, -90f, 360f * filled, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                } else {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

/**
 * In place of the tools while nodes are marked: close, how many, mark all or none, move to
 * another category (its names unfold below) and delete. Managed nodes move but are never deleted.
 */
@Composable
private fun SelectionBar(
    count: Int,
    allMarked: Boolean,
    deletable: Int,
    moving: Boolean,
    targets: List<NodeGroup>,
    onClose: () -> Unit,
    onMarkAll: () -> Unit,
    onToggleMove: () -> Unit,
    onMove: (NodeGroup) -> Unit,
    onDelete: () -> Unit,
) {
    val counted = pluralStringResource(R.plurals.kone_nodes_selected, count, count)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorAction(Icons.Default.Close, stringResource(R.string.kone_nodes_clear_selection), onClick = onClose)
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val more = targetState > initialState
                    (slideInVertically(tween(240)) { if (more) it else -it } + fadeIn(tween(240))) togetherWith
                        (slideOutVertically(tween(200)) { if (more) -it else it } + fadeOut(tween(160)))
                },
                modifier = Modifier.semantics { contentDescription = counted },
                label = "markedCount",
            ) { shown ->
                BText(
                    shown.toString(),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.weight(1f))
            EditorAction(
                if (allMarked) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                stringResource(if (allMarked) R.string.kone_nodes_select_none else R.string.kone_nodes_select_all),
                onClick = onMarkAll,
            )
            if (targets.isNotEmpty()) {
                EditorAction(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.kone_nodes_move), on = moving, onClick = onToggleMove)
            }
            if (deletable > 0) DeleteMarked(deletable, onDelete)
        }
        AnimatedVisibility(
            visible = moving && targets.isNotEmpty(),
            enter = io.nekohasekai.sfa.compose.theme.KnineMotion.reveal(),
            exit = io.nekohasekai.sfa.compose.theme.KnineMotion.conceal(),
        ) {
            MoveTargets(targets, onMove)
        }
    }
}

/** Delete, asked twice in place: the bin turns into the words, which delete; left alone, they turn back. */
@Composable
private fun DeleteMarked(count: Int, onDelete: () -> Unit) {
    val danger = dangerInk(LocalContentColor.current)
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    val label = pluralStringResource(R.plurals.kone_nodes_delete, count, count)
    AnimatedContent(
        targetState = armed,
        transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
        label = "deleteMarked",
    ) { asking ->
        if (asking) {
            val pill = RoundedCornerShape(17.dp)
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .height(34.dp)
                    .clip(pill)
                    .border(1.dp, danger.copy(alpha = 0.6f), pill)
                    .clickable(role = Role.Button) {
                        armed = false
                        onDelete()
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BText(label, style = MaterialTheme.typography.labelLarge, color = danger, maxLines = 1, softWrap = false)
            }
        } else {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { armed = true }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = danger, modifier = Modifier.size(19.dp))
            }
        }
    }
}

/** The other categories as names in a line; tapping one moves the marked nodes there. */
@Composable
private fun MoveTargets(targets: List<NodeGroup>, onMove: (NodeGroup) -> Unit) {
    val ink = LocalContentColor.current
    val shape = RoundedCornerShape(16.dp)
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        itemsIndexed(targets, key = { _, group -> group.id }) { _, group ->
            Row(
                Modifier
                    .clip(shape)
                    .border(0.8.dp, ink.copy(alpha = 0.3f), shape)
                    .clickable(role = Role.Button) { onMove(group) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                group.flag?.let {
                    FlagGlyph(it, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                }
                BText(group.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
    }
}
