package io.nekohasekai.sfa.compose.screen.dashboard

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.theme.knineSurface
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The ways in that the top bar's Import offers; the profile card owns what they launch. */
class KnineImportActions(
    val clipboard: () -> Unit,
    val scan: () -> Unit,
    val file: () -> Unit,
    val manual: () -> Unit,
)

private class BloomItem(val icon: ImageVector, val label: String, val lead: Boolean, val action: () -> Unit)

/**
 * Where [count] items sit on the quarter dial: from beside the menu button down to below it,
 * or, opened from a button low on the screen, from above it round to its right.
 */
private fun bloomAngles(count: Int, rising: Boolean): List<Float> {
    val step = 90f / (count - 1).coerceAtLeast(1)
    return List(count) { index -> if (rising) 270f + step * index else 180f - step * index }
}

/**
 * The ways to import, tossed out of the menu button onto a quarter dial just under the top
 * bar, over a soft mist instead of a card; they fly back in when one is chosen. The clipboard
 * is filled in when it holds text.
 */
@Composable
internal fun ImportBloom(anchorInRoot: Offset, actions: KnineImportActions, onClosed: () -> Unit) {
    val context = LocalContext.current
    val ink = LocalContentColor.current
    val surface = knineSurface()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val open = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    // Only the clip's description is read; reading its text would show the system paste notice.
    val clipboardHasText = remember {
        context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.let {
            it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        } == true
    }
    LaunchedEffect(Unit) { open.animateTo(1f, tween(720, easing = LinearEasing)) }

    fun close(chosen: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        chosen?.invoke()
        scope.launch {
            open.animateTo(0f, tween(300, easing = LinearEasing))
            onClosed()
        }
    }
    BackHandler { close() }

    val items = listOf(
        BloomItem(Icons.Default.ContentPaste, "Clipboard", clipboardHasText, actions.clipboard),
        BloomItem(Icons.Default.QrCodeScanner, "Scan QR code", false, actions.scan),
        BloomItem(Icons.Outlined.Description, "File", false, actions.file),
        BloomItem(Icons.Outlined.Edit, "Manual", false, actions.manual),
    )
    val halo = TextStyle(shadow = Shadow(surface.copy(alpha = 0.95f), Offset.Zero, with(density) { 12.dp.toPx() }))
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) { detectTapGestures { close() } },
    ) {
        val start = origin ?: return@Box
        val anchor = anchorInRoot - start
        val rising = anchorInRoot.y > screenHeight / 2f
        val angles = bloomAngles(items.size, rising)
        with(density) {
            // Centred just under the top bar and a little in from the edge, so nothing sits under the bar;
            // opened from below, centred a little above the button so the dial clears it.
            val center = if (rising) Offset(anchor.x, anchor.y - 40.dp.toPx()) else Offset(anchor.x - 10.dp.toPx(), anchor.y + 62.dp.toPx())
            // Five items need a wider arc to keep their circles apart.
            val radius = (if (items.size > 4) 150 else 130).dp.toPx()
            val reach = radius + 52.dp.toPx()
            val t = open.value
            val chrome = ((t - 0.2f) / 0.5f).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxSize()) {
                val mist = t.coerceIn(0f, 1f)
                val spread = radius + 150.dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        0f to surface.copy(alpha = 0.9f * mist),
                        0.55f to surface.copy(alpha = 0.62f * mist),
                        1f to Color.Transparent,
                        center = center,
                        radius = spread,
                    ),
                    radius = spread,
                    center = center,
                )
                drawArc(
                    ink.copy(alpha = 0.26f * chrome),
                    startAngle = if (rising) 270f else 90f,
                    sweepAngle = 90f * chrome,
                    useCenter = false,
                    topLeft = center - Offset(radius, radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            items.forEachIndexed { i, item ->
                val angle = Math.toRadians(angles[i].toDouble())
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val target = center + direction * radius
                // Closer items leave first and come back last.
                val p = backOut(((t - i * 0.09f) / 0.62f).coerceIn(0f, 1f))
                val at = anchor + (target - anchor) * p
                Box(
                    Modifier
                        .centeredAt(at) {
                            val s = 0.3f + 0.7f * p
                            scaleX = s
                            scaleY = s
                            rotationZ = (1f - p) * -90f
                            alpha = p.coerceIn(0f, 1f)
                        }
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (item.lead) ink else surface.copy(alpha = 0.55f))
                        .border(0.8.dp, ink.copy(alpha = if (item.lead) 0f else 0.22f), CircleShape)
                        .clickable(enabled = !closing && p > 0.5f) { close(item.action) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = if (item.lead) surface else ink, modifier = Modifier.size(23.dp))
                }
                val q = ((t - 0.32f - i * 0.06f) / 0.4f).coerceIn(0f, 1f)
                Text(
                    item.label,
                    color = ink,
                    fontSize = 13.sp,
                    fontWeight = if (item.lead) FontWeight.SemiBold else FontWeight.Medium,
                    style = halo,
                    maxLines = 1,
                    modifier = Modifier.centeredAt(center + direction * (reach - (1f - q) * 10.dp.toPx())) { alpha = q },
                )
            }
        }
    }
}

/** Lays the content out at its natural size, centred on [at] of a parent-sized box. */
private fun Modifier.centeredAt(at: Offset, layer: GraphicsLayerScope.() -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeWithLayer((at.x - placeable.width / 2f).roundToInt(), (at.y - placeable.height / 2f).roundToInt(), layerBlock = layer)
    }
}
