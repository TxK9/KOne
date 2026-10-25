package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.nekohasekai.sfa.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.theme.KnineSwitcherStyle
import io.nekohasekai.sfa.compose.theme.knineSurface
import io.nekohasekai.sfa.compose.util.FlagGlyph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class SwitcherItem(val label: String, val count: Int? = null, val flag: String? = null)

private const val ITEM_SPAN = 0.55f
private const val ITEM_STAGGER = 0.06f
private const val DIAL_STEP_DEGREES = 15f
private const val WHEEL_STEP_DEGREES = 22f
private const val DIAL_TYPE_SP = 19f
private val DIAL_LABEL_PAD = 10.dp

/**
 * Spacing on the dial. Two neighbouring names never touch, whichever of them is in focus, and
 * the type shrinks a little, never below [MIN_TYPE], until the neighbour of a focused name
 * fits on screen beside it. Stops are degrees along the dial, the first name at 0.
 */
internal class DialSpacing(val type: Float, val stops: FloatArray) {
    fun angleAt(position: Float): Float {
        if (stops.size < 2) return position * DIAL_STEP_DEGREES
        val lower = floor(position).toInt().coerceIn(0, stops.size - 2)
        return stops[lower] + (stops[lower + 1] - stops[lower]) * (position - lower)
    }

    fun stepAfter(position: Float): Float {
        if (stops.size < 2) return DIAL_STEP_DEGREES
        val lower = floor(position).toInt().coerceIn(0, stops.size - 2)
        return stops[lower + 1] - stops[lower]
    }

    companion object {
        const val FOCUS_SCALE = 1.1f
        const val SIDE_SCALE = 0.9f
        const val MIN_TYPE = 0.8f

        /** [widthAt] is a label's full width at a type factor, including [pad] on each side. */
        fun fit(count: Int, widthAt: (Int, Float) -> Float, radius: Float, halfScreen: Float, pad: Float, gap: Float): DialSpacing {
            var type = 1f
            while (true) {
                val widths = FloatArray(count) { widthAt(it, type) }
                val stops = FloatArray(count)
                var fits = true
                for (i in 1 until count) {
                    val a = widths[i - 1]
                    val b = widths[i]
                    val arc = max(FOCUS_SCALE * a + SIDE_SCALE * b, FOCUS_SCALE * b + SIDE_SCALE * a) / 2f + gap
                    val step = max(DIAL_STEP_DEGREES, (arc / radius) * 180f / PI.toFloat())
                    stops[i] = stops[i - 1] + step
                    // The neighbour's name, not its padding, has to stay on screen.
                    val reach = radius * sin(radians(step)) + SIDE_SCALE * (max(a, b) / 2f - pad)
                    if (reach > halfScreen) fits = false
                }
                if (fits || type <= MIN_TYPE) return DialSpacing(type, stops)
                type = (type - 0.05f).coerceAtLeast(MIN_TYPE)
            }
        }
    }
}

/** Ease-out with a small overshoot, so each name settles as if it was tossed into place. */
internal fun backOut(t: Float): Float {
    val c1 = 1.5f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

private fun radians(degrees: Float): Float = (degrees * PI / 180.0).toFloat()

/**
 * Picker without cards: names fly out of the capsule at [anchorInRoot] and arrange on a
 * dial, a 3D wheel or as floating type. [KnineSwitcherStyle.Cards] keeps a glass grid.
 * [bottomInset] is the part of this overlay hidden behind the navigation bar.
 */
@Composable
internal fun KnineSwitcher(
    style: KnineSwitcherStyle,
    title: String,
    items: List<SwitcherItem>,
    initial: Int,
    anchorInRoot: Offset,
    bottomInset: Dp,
    hint: (Int) -> String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onManage: ((Int) -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val ink = LocalContentColor.current
    val surface = knineSurface()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val last = items.lastIndex
    val start = initial.coerceIn(0, last)
    val open = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(start.toFloat()) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var origin by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) { open.animateTo(1f, tween(950, easing = LinearEasing)) }

    fun close(result: Int?) {
        if (closing) return
        closing = true
        motion?.cancel()
        scope.launch {
            open.animateTo(0f, tween(420, easing = LinearEasing))
            if (result != null) onSelect(result) else onDismiss()
        }
    }

    fun glideTo(target: Int, then: (() -> Unit)? = null) {
        motion?.cancel()
        motion = scope.launch {
            animate(position, target.toFloat(), animationSpec = spring(dampingRatio = 0.82f, stiffness = 320f)) { value, _ ->
                position = value
            }
            then?.invoke()
        }
    }

    BackHandler { close(null) }

    // Closer names arrive first; a linear clock keeps the same stagger in reverse when closing.
    fun progress(index: Int): Float {
        val delayFraction = min(abs(index - start), 6) * ITEM_STAGGER
        return backOut(((open.value - delayFraction) / ITEM_SPAN).coerceIn(0f, 1f))
    }
    val chrome = ((open.value - 0.25f) / 0.5f).coerceIn(0f, 1f)
    val focus = position.roundToInt().coerceIn(0, last)
    val halo = TextStyle(shadow = Shadow(surface.copy(alpha = 0.95f), Offset.Zero, with(density) { 14.dp.toPx() }))
    val measurer = rememberTextMeasurer()
    // A dial label's full width at a type factor: name, flag, count and the touch padding.
    fun labelWidth(item: SwitcherItem, type: Float): Float = with(density) {
        val name = measurer.measure(item.label, TextStyle(fontSize = (DIAL_TYPE_SP * type).sp, fontWeight = FontWeight.SemiBold), maxLines = 1).size.width
        val count = item.count?.takeIf { it > 0 }?.let { measurer.measure(it.toString(), TextStyle(fontSize = 11.sp), maxLines = 1).size.width + 3.dp.toPx() } ?: 0f
        name + count + (if (item.flag != null) 26.dp.toPx() else 0f) + 2 * DIAL_LABEL_PAD.toPx()
    }
    val wrappedManage = onManage?.let { action -> { close(null); action(focus) } }
    val wrappedAdd = onAdd?.let { action -> { close(null); action() } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) { detectTapGestures { close(null) } },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val base = h - with(density) { bottomInset.toPx() }
        val anchor = anchorInRoot - (origin ?: Offset.Zero)
        fun px(value: Dp) = with(density) { value.toPx() }

        // Shapeless fog: the photograph stays visible, only the names get support.
        val fogY = base - px(if (style == KnineSwitcherStyle.Floating) 250.dp else 155.dp)
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val alpha = open.value.coerceIn(0f, 1f)
                    val center = Offset(w / 2f, fogY)
                    scale(scaleX = 1f, scaleY = 0.55f, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to surface.copy(alpha = 0.84f * alpha),
                                0.5f to surface.copy(alpha = 0.55f * alpha),
                                1f to Color.Transparent,
                                center = center,
                                radius = w * 0.95f,
                            ),
                            radius = w * 0.95f,
                            center = center,
                        )
                    }
                },
        )

        when (style) {
            KnineSwitcherStyle.Dial, KnineSwitcherStyle.Wheel -> {
                val dial = style == KnineSwitcherStyle.Dial
                val cx = w / 2f
                val dialCy = base + px(200.dp)
                val dialR = px(360.dp)
                val track = px(390.dp)
                val wheelCy = base - px(160.dp)
                val wheelR = px(120.dp)
                val spacing = remember(items, w, density) {
                    DialSpacing.fit(
                        count = items.size,
                        widthAt = { i, type -> labelWidth(items[i], type) },
                        radius = dialR,
                        halfScreen = w / 2f - px(6.dp),
                        pad = px(DIAL_LABEL_PAD),
                        gap = px(8.dp),
                    )
                }
                // Where the dial has turned to, in degrees along the same stops as the names.
                fun turned(at: Float): Float = spacing.angleAt(at)
                val currentSpacing by rememberUpdatedState(spacing)

                Canvas(Modifier.fillMaxSize()) {
                    if (dial) {
                        drawCircle(ink.copy(alpha = 0.3f * chrome), radius = track, center = Offset(cx, dialCy), style = Stroke(width = 0.8.dp.toPx()))
                        items.indices.forEach { i ->
                            val a = radians(-90f + spacing.stops[i] - turned(position))
                            drawCircle(ink.copy(alpha = 0.55f * chrome), radius = 2.dp.toPx(), center = Offset(cx + track * cos(a), dialCy + track * sin(a)))
                        }
                        drawCircle(surface.copy(alpha = 0.7f * chrome), radius = 7.5.dp.toPx(), center = Offset(cx, dialCy - track))
                        drawCircle(ink.copy(alpha = chrome), radius = 3.5.dp.toPx(), center = Offset(cx, dialCy - track))
                    } else {
                        listOf(wheelCy - 26.dp.toPx(), wheelCy + 26.dp.toPx()).forEach { y ->
                            drawLine(ink.copy(alpha = 0.3f * chrome), Offset(60.dp.toPx(), y), Offset(w - 60.dp.toPx(), y), strokeWidth = 0.7.dp.toPx())
                        }
                    }
                }
                SwitcherTitle(title, if (dial) dialCy - track - px(44.dp) else wheelCy - px(152.dp), chrome, halo)

                // Dragging rotates; releasing settles on the nearest name.
                var raw by remember { mutableFloatStateOf(position) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(style) {
                            detectDragGestures(
                                onDragStart = {
                                    motion?.cancel()
                                    raw = position
                                },
                                onDragEnd = { if (!closing) glideTo(position.roundToInt().coerceIn(0, last)) },
                                onDragCancel = { if (!closing) glideTo(position.roundToInt().coerceIn(0, last)) },
                            ) { change, amount ->
                                change.consume()
                                // One name per finger travel of the arc between it and the next.
                                val stepPx = if (dial) dialR * radians(currentSpacing.stepAfter(position)) else px(46.dp)
                                raw -= (if (dial) amount.x else amount.y) / stepPx
                                position = when {
                                    raw < 0f -> raw * 0.35f
                                    raw > last -> last + (raw - last) * 0.35f
                                    else -> raw
                                }
                            }
                        },
                ) {
                    items.forEachIndexed { i, item ->
                        val p = progress(i)
                        val d = i - position
                        val targetX: Float
                        val targetY: Float
                        val rotation: Float
                        val tilt: Float
                        val scale: Float
                        val visibility: Float
                        if (dial) {
                            val turn = spacing.stops[i] - turned(position)
                            val a = radians(-90f + turn)
                            targetX = cx + dialR * cos(a)
                            targetY = dialCy + dialR * sin(a)
                            rotation = turn
                            tilt = 0f
                            scale = DialSpacing.FOCUS_SCALE - min(abs(d), 3f) * (DialSpacing.FOCUS_SCALE - DialSpacing.SIDE_SCALE)
                            visibility = max(0f, 1f - abs(d) * 0.32f)
                        } else {
                            val a = d * WHEEL_STEP_DEGREES
                            targetX = cx
                            targetY = wheelCy + wheelR * sin(radians(a))
                            rotation = 0f
                            tilt = -a
                            scale = 1f
                            visibility = if (abs(a) < 80f) cos(radians(a)).coerceAtLeast(0f).pow(1.6f) else 0f
                        }
                        val shown = visibility * p.coerceIn(0f, 1f)
                        SwitcherLabel(
                            item = item,
                            focused = i == focus,
                            big = !dial,
                            type = if (dial) spacing.type else 1f,
                            halo = halo,
                            enabled = shown > 0.25f && !closing,
                            modifier = Modifier.placeCentered(
                                x = anchor.x + (targetX - anchor.x) * p,
                                y = anchor.y + (targetY - anchor.y) * p,
                            ) {
                                rotationZ = rotation * p
                                rotationX = tilt
                                cameraDistance = 12f * this.density
                                val s = 0.3f + (scale - 0.3f) * p
                                scaleX = s
                                scaleY = s
                                alpha = shown
                            },
                            onClick = {
                                if (i == focus) {
                                    close(i)
                                } else {
                                    glideTo(i) {
                                        scope.launch {
                                            delay(120)
                                            close(i)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                SwitcherFooter(base, hint(focus), chrome, halo, wrappedManage, wrappedAdd)
            }

            KnineSwitcherStyle.Floating -> {
                val top = base - px(440.dp)
                val listHeight = (base - px(70.dp) - top).roundToInt().coerceAtLeast(0)
                SwitcherTitle(title, top - px(24.dp), chrome, halo)
                var chosen by remember { mutableStateOf(start) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = listHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, (top + (listHeight - placeable.height) / 2f).roundToInt())
                            }
                        },
                ) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState(), enabled = !closing)
                            .padding(start = 34.dp, end = 28.dp),
                    ) {
                        items.forEachIndexed { i, item ->
                            val p = progress(i)
                            val focused = i == chosen
                            Row(
                                Modifier
                                    .graphicsLayer {
                                        alpha = p.coerceIn(0f, 1f) * (if (focused) 1f else 0.62f)
                                        translationY = (1f - p) * 16.dp.toPx()
                                    }
                                    .semantics { selected = focused }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        enabled = !closing,
                                        role = Role.Button,
                                    ) {
                                        chosen = i
                                        close(i)
                                    }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(if (focused) 22.dp else 0.dp).height(1.6.dp).background(ink))
                                Spacer(Modifier.width(if (focused) 10.dp else 0.dp))
                                item.flag?.let {
                                    FlagGlyph(it, size = 24.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    item.label,
                                    color = ink,
                                    fontSize = 27.sp,
                                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                                    style = halo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.count?.takeIf { it > 0 }?.let { count ->
                                    Text(
                                        count.toString(),
                                        color = ink,
                                        fontSize = 11.sp,
                                        style = halo,
                                        modifier = Modifier.padding(start = 3.dp).align(Alignment.Top),
                                    )
                                }
                            }
                        }
                    }
                }
                SwitcherFooter(base, hint(chosen), chrome, halo, wrappedManage, wrappedAdd)
            }

            KnineSwitcherStyle.Cards -> CardsSwitcher(
                title = title,
                items = items,
                current = start,
                anchorInRoot = anchorInRoot,
                bottomInset = bottomInset,
                reveal = open.value,
                progress = ::progress,
                ink = ink,
                surface = surface,
                enabled = !closing,
                onPick = { close(it) },
                onManage = wrappedManage,
                onAdd = wrappedAdd,
            )
        }
    }
}

/** Lays the content out at its natural size, centred on ([x], [y]) of a parent-sized box. */
private fun Modifier.placeCentered(x: Float, y: Float, layer: GraphicsLayerScope.() -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeWithLayer((x - placeable.width / 2f).roundToInt(), (y - placeable.height / 2f).roundToInt(), layerBlock = layer)
    }
}

/** Full-width content whose vertical centre sits at [y] of a parent-sized box. */
private fun Modifier.placeCenteredY(y: Float, layer: GraphicsLayerScope.() -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeWithLayer(0, (y - placeable.height / 2f).roundToInt(), layerBlock = layer)
    }
}

@Composable
private fun SwitcherTitle(title: String, centerY: Float, alpha: Float, halo: TextStyle) {
    Text(
        title,
        color = LocalContentColor.current,
        fontSize = 12.sp,
        letterSpacing = 3.6.sp,
        textAlign = TextAlign.Center,
        style = halo,
        modifier = Modifier
            .fillMaxWidth()
            .placeCenteredY(centerY) { this.alpha = alpha * 0.8f },
    )
}

@Composable
private fun SwitcherFooter(base: Float, hint: String, alpha: Float, halo: TextStyle, onManage: (() -> Unit)?, onAdd: (() -> Unit)?) {
    val ink = LocalContentColor.current
    val centerY = base - with(LocalDensity.current) { 34.dp.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .placeCenteredY(centerY) {
                this.alpha = alpha
                translationY = (1f - alpha) * 8.dp.toPx()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onManage != null) FooterAction(Icons.Default.Edit, stringResource(R.string.kone_dashboard_organize), halo, onManage) else Spacer(Modifier.width(64.dp))
        Text(
            hint,
            color = ink.copy(alpha = 0.82f),
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            style = halo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onAdd != null) FooterAction(Icons.Default.Add, "New", halo, onAdd) else Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun FooterAction(icon: ImageVector, label: String, halo: TextStyle, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, style = halo)
    }
}

@Composable
private fun SwitcherLabel(
    item: SwitcherItem,
    focused: Boolean,
    big: Boolean,
    type: Float,
    halo: TextStyle,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val ink = LocalContentColor.current
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics { selected = focused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DIAL_LABEL_PAD, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.flag?.let {
            FlagGlyph(it, size = if (big) 22.dp else 20.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            item.label,
            color = ink,
            fontSize = if (big) 21.sp else (DIAL_TYPE_SP * type).sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            style = halo,
            maxLines = 1,
        )
        item.count?.takeIf { it > 0 }?.let { count ->
            Text(count.toString(), color = ink, fontSize = 11.sp, style = halo, modifier = Modifier.padding(start = 3.dp).align(Alignment.Top))
        }
    }
}

/** The earlier glass-card grid, kept as a appearance option. */
@Composable
private fun CardsSwitcher(
    title: String,
    items: List<SwitcherItem>,
    current: Int,
    anchorInRoot: Offset,
    bottomInset: Dp,
    reveal: Float,
    progress: (Int) -> Float,
    ink: Color,
    surface: Color,
    enabled: Boolean,
    onPick: (Int) -> Unit,
    onManage: (() -> Unit)?,
    onAdd: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val centers = remember { mutableStateMapOf<Int, Offset>() }
    val shown = items.withIndex().filter { (_, item) -> query.isBlank() || item.label.contains(query.trim(), ignoreCase = true) }
    val addIndex = -1
    val cells: List<Int> = shown.map { it.index } + (if (onAdd != null && query.isBlank()) listOf(addIndex) else emptyList())
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bottomInset + 8.dp)
                .fillMaxWidth()
                .onGloballyPositioned { panelOrigin = it.boundsInRoot().topLeft }
                .drawWithContent {
                    // A circle grows out of the capsule; nothing outside it is drawn yet.
                    val radius = 22.dp.toPx() + hypot(size.width, size.height) * reveal.coerceIn(0f, 1f) * 1.2f
                    val path = Path().apply { addOval(Rect(anchorInRoot - panelOrigin, radius)) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
                .clip(RoundedCornerShape(30.dp))
                .background(surface.copy(alpha = 0.94f))
                .border(0.7.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(30.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.kone_dashboard_item_count, items.size, items.size), color = ink.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (onManage != null) {
                    Text(
                        stringResource(R.string.kone_dashboard_organize),
                        color = ink,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onManage).padding(6.dp),
                    )
                }
            }
            if (items.size > 6) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ink.copy(alpha = 0.07f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(color = ink, fontSize = 14.5.sp),
                        cursorBrush = SolidColor(ink),
                        modifier = Modifier.weight(1f),
                        decorationBox = { field ->
                            if (query.isEmpty()) Text(stringResource(R.string.kone_dashboard_search_categories), color = ink.copy(alpha = 0.55f), fontSize = 14.5.sp)
                            field()
                        },
                    )
                }
            }
            Column(
                Modifier
                    .heightIn(max = 330.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cells.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { index ->
                            val isAdd = index == addIndex
                            val p = progress(if (isAdd) items.size else index)
                            val center = centers[index]
                            val selected = index == current
                            val fg = if (selected) surface else ink
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(66.dp)
                                    .onGloballyPositioned { centers[index] = it.boundsInRoot().center }
                                    .graphicsLayer {
                                        val from = center?.let { anchorInRoot - it } ?: Offset.Zero
                                        translationX = from.x * (1f - p)
                                        translationY = from.y * (1f - p)
                                        val s = 0.35f + 0.65f * p
                                        scaleX = s
                                        scaleY = s
                                        alpha = p.coerceIn(0f, 1f)
                                    }
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (selected) ink else ink.copy(alpha = if (isAdd) 0f else 0.06f))
                                    .border(if (isAdd) 1.dp else 0.7.dp, ink.copy(alpha = if (isAdd) 0.3f else 0.1f), RoundedCornerShape(18.dp))
                                    .semantics { this.selected = selected }
                                    .clickable(enabled = enabled, role = Role.Button) { if (isAdd) onAdd?.invoke() else onPick(index) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (isAdd) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.kone_dashboard_new_category), color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    val item = items[index]
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        item.flag?.let {
                                            FlagGlyph(it, size = 18.dp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(item.label, color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    val count = item.count
                                    Text(
                                        if (count == null) "" else if (count == 0) stringResource(R.string.kone_dashboard_empty) else pluralStringResource(R.plurals.kone_dashboard_node_count, count, count),
                                        color = fg.copy(alpha = 0.78f),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
