package io.nekohasekai.sfa.compose.screen.dashboard

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
import androidx.compose.foundation.verticalScroll
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
    var expanded by remember { mutableStateOf(false) }
    val listMax = (LocalConfiguration.current.screenHeightDp * 0.42f).dp

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
        CategoryTabs(
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .then(if (height.value > 0f) Modifier.height(with(density) { height.value.toDp() }) else Modifier),
            verticalAlignment = Alignment.Top,
            pageSpacing = 16.dp,
            beyondViewportPageCount = groups.size,
            userScrollEnabled = editing == null,
            key = { groups.getOrNull(it)?.id ?: it },
        ) { index ->
            val group = groups.getOrNull(index) ?: return@HorizontalPager
            val all = floating || expanded
            // Only a list too long for the screen scrolls; a short one leaves the swipe that folds the panel alone.
            val long = all && group.profiles.size * NODE_ROW_DP > listMax.value
            NodePage(
                group = group,
                selectedId = selectedId,
                clear = clear || floating,
                all = all,
                fromBottom = floating,
                rise = { rise.value },
                onToggleAll = if (floating) null else ({ expanded = !expanded }),
                onSelect = onSelect,
                onEdit = onEdit,
                onDelete = onDelete,
                onImport = onImport,
                modifier = Modifier
                    .naturalHeight { if (heights[group.id] != it) heights[group.id] = it }
                    .graphicsLayer {
                        // The page sliding away dims, the arriving one comes up to full.
                        val distance = abs(index - pagerState.currentPage - pagerState.currentPageOffsetFraction).coerceAtMost(1f)
                        alpha = 1f - distance * 0.6f
                    }
                    .then(if (long) Modifier.heightIn(max = listMax).verticalScroll(rememberScrollState()) else Modifier),
            )
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
                    .combinedClickable(
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
                    .clickable(onClick = ::finish),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = "Done", tint = surface, modifier = Modifier.size(18.dp))
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
private fun EditorAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
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
                        .clickable(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "More icons", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FlagChoice(code: String?, selected: Boolean, appear: () -> Float, onTap: () -> Unit) {
    val ink = LocalContentColor.current
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
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (code != null) {
            FlagGlyph(code, size = 22.dp)
        } else {
            Icon(Icons.Default.Close, contentDescription = "No flag", modifier = Modifier.size(16.dp).graphicsLayer { alpha = 0.6f })
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
) {
    Column(modifier.fillMaxWidth()) {
        if (group.profiles.isEmpty() && onImport != null) {
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
            return@Column
        }
        if (group.profiles.isEmpty()) {
            BText(
                "No nodes yet",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .graphicsLayer { alpha = 0.7f },
            )
            return@Column
        }
        val shown = if (all) group.profiles else visibleNodes(group, selectedId)
        shown.forEachIndexed { i, profile ->
            key(profile.id) {
            SwipeNode(
                deletable = onDelete != null && !io.nekohasekai.sfa.managed.ManagedState.isManaged(profile.id),
                onDelete = { onDelete?.invoke(profile.id) },
            ) {
            NodeRow(
                profile = profile,
                selected = profile.id == selectedId,
                clear = clear,
                onSelect = { onSelect(profile.id) },
                onEdit = { onEdit(profile.id) },
                modifier = Modifier.graphicsLayer {
                    // One row after another; the lifted list starts from the row nearest the folded line.
                    val order = (if (fromBottom) shown.size - 1 - i else i).coerceAtMost(8)
                    val p = FastOutSlowInEasing.transform(((rise() - order * 0.07f) / 0.5f).coerceIn(0f, 1f))
                    alpha = p
                    translationY = (1f - p) * 18.dp.toPx()
                },
            )
            }
            }
        }
        if (onToggleAll != null && group.profiles.size > SHOWN_NODES) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleAll)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BText(if (all) "Collapse" else "All ${group.profiles.size} nodes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(if (all) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
