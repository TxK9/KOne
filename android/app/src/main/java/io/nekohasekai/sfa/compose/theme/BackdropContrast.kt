package io.nekohasekai.sfa.compose.theme

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.FloatingActionButton as MaterialFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow

internal data class BackdropPixels(
    val width: Int,
    val height: Int,
    val luminance: FloatArray,
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
    val colors: IntArray = IntArray(0),
)

internal data class BackdropContext(
    val pixels: BackdropPixels?,
    val bounds: Rect,
    val previousPixels: BackdropPixels? = null,
    val focus: BackdropFocus = BackdropFocus(),
    val previousFocus: BackdropFocus = BackdropFocus(),
    val theme: WallpaperTheme = WallpaperTheme(true),
    val layoutTheme: WallpaperTheme = theme,
    val onReadingTopChanged: ((Float?) -> Unit)? = null,
    val painter: Painter? = null,
    // Follows the photo on screen, so the panel moves while a wallpaper change has faded it out.
    val panelAtTop: Boolean = false,
)

internal val LocalBackdrop = staticCompositionLocalOf<BackdropContext?> { null }

internal fun loadBackdropPixels(resources: Resources, resourceId: Int): BackdropPixels? =
    loadBackdropPixels { options -> BitmapFactory.decodeResource(resources, resourceId, options) }

internal fun loadBackdropPixels(filePath: String): BackdropPixels? =
    loadBackdropPixels { options -> BitmapFactory.decodeFile(filePath, options) }

private fun loadBackdropPixels(decode: (BitmapFactory.Options) -> android.graphics.Bitmap?): BackdropPixels? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(options)
    val sourceWidth = options.outWidth
    val sourceHeight = options.outHeight
    var sample = 1
    // This is only the stable theme decision map, not the displayed photograph.
    while (max(options.outWidth, options.outHeight) / (sample * 2) >= 192) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    val bitmap = decode(options) ?: return null
    return try {
        val luminance = FloatArray(bitmap.width * bitmap.height)
        val colors = IntArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                colors[y * bitmap.width + x] = pixel
                luminance[y * bitmap.width + x] = ColorUtils.calculateLuminance(pixel).toFloat()
            }
        }
        BackdropPixels(bitmap.width, bitmap.height, luminance, sourceWidth, sourceHeight, colors)
    } finally {
        bitmap.recycle()
    }
}

internal fun loadDisplayedBackdrop(path: String): android.graphics.Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    var sample = 1
    while (max(options.outWidth, options.outHeight) / sample > 2560) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    return BitmapFactory.decodeFile(path, options)
}

// Every decoded pixel intersecting the reading area is considered, with a one-pixel
// margin. This is a reduced image map; it cannot certify original-image detail.
internal fun backdropColors(backdrop: BackdropContext?, area: Rect): List<Int> {
    val pixels = backdrop?.pixels ?: return emptyList()
    if (backdrop.previousPixels != null) {
        // sRGB crossfade intermediates are not represented by endpoint
        // luminances. Bound the short transition, then remeasure the new image.
        return listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    }
    val current = sampledBackdropColors(pixels, backdrop.bounds, area, backdrop.focus)
    return current
}

internal fun sampledBackdropColors(pixels: BackdropPixels, root: Rect, area: Rect, focus: BackdropFocus): List<Int> {
    if (root.width <= 0f || root.height <= 0f || area.width <= 0f || area.height <= 0f) return emptyList()
    val framing = BackdropFraming.transform(pixels.sourceWidth, pixels.sourceHeight, root.width, root.height, focus)
    val local = area.translate(Offset(-root.left, -root.top))
    fun imageX(x: Float) = ((x - framing.x) / framing.width * pixels.width).toInt().coerceIn(0, pixels.width - 1)
    fun imageY(y: Float) = ((y - framing.y) / framing.height * pixels.height).toInt().coerceIn(0, pixels.height - 1)
    val startX = (imageX(local.left) - 1).coerceAtLeast(0)
    val endX = (imageX(local.right) + 1).coerceAtMost(pixels.width - 1)
    val startY = (imageY(local.top) - 1).coerceAtLeast(0)
    val endY = (imageY(local.bottom) + 1).coerceAtMost(pixels.height - 1)
    val samples = ArrayList<Int>((endX - startX + 1) * (endY - startY + 1))
    for (row in startY..endY) {
        for (column in startX..endX) {
            val index = row * pixels.width + column
            if (pixels.colors.size == pixels.width * pixels.height) {
                val pixel = pixels.colors[index]
                samples += pixel
                if ((pixel ushr 24) != 255) {
                    samples += 0xFF000000.toInt()
                    samples += 0xFFFFFFFF.toInt()
                }
            }
            else {
                // Only the startup fallback lacks RGB values.
                val value = pixels.luminance[index].toDouble()
                val srgb = if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055
                val grey = (srgb * 255).roundToInt().coerceIn(0, 255)
                samples += (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            }
        }
    }
    return samples
}

internal fun backdropColorScheme(lightInk: Boolean) =
    (if (lightInk) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()).copy(
        primary = if (lightInk) Color(KninePalette.LIGHT_ACCENT) else Color(KninePalette.DARK_ACCENT),
        onPrimary = if (lightInk) Color(KninePalette.DARK_INK) else Color(KninePalette.LIGHT_INK),
        secondary = if (lightInk) Color(KninePalette.LIGHT_ACCENT) else Color(KninePalette.DARK_ACCENT),
        tertiary = if (lightInk) Color(KninePalette.LIGHT_ACCENT) else Color(KninePalette.DARK_ACCENT),
        primaryContainer = Color.Transparent,
        secondaryContainer = Color.Transparent,
        tertiaryContainer = Color.Transparent,
        onPrimaryContainer = if (lightInk) Color(KninePalette.LIGHT_INK) else Color(KninePalette.DARK_INK),
        onSecondaryContainer = if (lightInk) Color(KninePalette.LIGHT_INK) else Color(KninePalette.DARK_INK),
        onTertiaryContainer = if (lightInk) Color(KninePalette.LIGHT_INK) else Color(KninePalette.DARK_INK),
        background = Color.Transparent,
        onBackground = if (lightInk) Color(KninePalette.LIGHT_INK) else Color(KninePalette.DARK_INK),
        surface = Color.Transparent,
        surfaceVariant = Color.Transparent,
        surfaceDim = Color.Transparent,
        surfaceBright = Color.Transparent,
        surfaceContainerLowest = Color.Transparent,
        surfaceContainerLow = Color.Transparent,
        surfaceContainer = Color.Transparent,
        surfaceContainerHigh = Color.Transparent,
        surfaceContainerHighest = Color.Transparent,
        onSurface = if (lightInk) Color(KninePalette.LIGHT_INK) else Color(KninePalette.DARK_INK),
        onSurfaceVariant = if (lightInk) Color(KninePalette.LIGHT_INK) else Color(KninePalette.DARK_INK),
        outline = Color(KninePalette.ink(lightInk)).copy(alpha = 0.36f),
        outlineVariant = Color(KninePalette.ink(lightInk)).copy(alpha = 0.14f),
        surfaceTint = Color.Transparent,
        error = if (lightInk) Color(0xFFFFB4AB) else Color(KninePalette.DARK_ERROR),
        onError = if (lightInk) Color(KninePalette.DARK_INK) else Color(KninePalette.LIGHT_INK),
    )

@Suppress("UNUSED_PARAMETER")
internal fun backdropTypography(base: Typography, lightInk: Boolean): Typography {
    val shadow = Shadow.None
    return base.copy(
        displayLarge = base.displayLarge.copy(shadow = shadow),
        displayMedium = base.displayMedium.copy(shadow = shadow),
        displaySmall = base.displaySmall.copy(shadow = shadow),
        headlineLarge = base.headlineLarge.copy(shadow = shadow),
        headlineMedium = base.headlineMedium.copy(shadow = shadow),
        headlineSmall = base.headlineSmall.copy(shadow = shadow),
        titleLarge = base.titleLarge.copy(shadow = shadow),
        titleMedium = base.titleMedium.copy(shadow = shadow),
        titleSmall = base.titleSmall.copy(shadow = shadow),
        bodyLarge = base.bodyLarge.copy(shadow = shadow),
        bodyMedium = base.bodyMedium.copy(shadow = shadow),
        bodySmall = base.bodySmall.copy(shadow = shadow),
        labelLarge = base.labelLarge.copy(shadow = shadow),
        labelMedium = base.labelMedium.copy(shadow = shadow),
        labelSmall = base.labelSmall.copy(shadow = shadow),
    )
}

@Composable
fun BackdropContrastScope(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val backdrop = LocalBackdrop.current
    if (backdrop == null) {
        Box(modifier = modifier, propagateMinConstraints = true) { content() }
        return
    }
    val lightInk = backdrop.theme.lightInk
    val colors = backdropColorScheme(lightInk)
        .let { if (LocalTransparentUi.current) it.copy(outline = Color.Transparent, outlineVariant = Color.Transparent) else it }
    val typography = backdropTypography(MaterialTheme.typography, lightInk)
    Box(
        modifier = modifier,
        propagateMinConstraints = true,
    ) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                ProvideTextStyle(typography.bodyLarge) { content() }
            }
        }
    }
}

// Same upstream Card call sites and content scopes; only the visible surface is removed.
@Suppress("UNUSED_PARAMETER")
@Composable
fun BackdropCard(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackdropContrastScope(modifier = modifier.readingBleed()) {
        WallpaperGlass(shape = shape) {
        MaterialCard(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = if (LocalTransparentUi.current) null else border,
            content = content,
        )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun BackdropCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackdropContrastScope(modifier = modifier.readingBleed()) {
        WallpaperGlass(shape = shape) {
        MaterialCard(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, disabledElevation = 0.dp),
            border = if (LocalTransparentUi.current) null else border,
            interactionSource = interactionSource,
            content = content,
        )
        }
    }
}


@Suppress("UNUSED_PARAMETER")
@Composable
fun BackdropFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    BackdropContrastScope(modifier = modifier) {
        MaterialFloatingActionButton(
            onClick = onClick,
            shape = shape,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            interactionSource = interactionSource,
            content = content,
        )
    }
}


@Suppress("UNUSED_PARAMETER")
@Composable
fun BackdropButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val hasBackdrop = LocalBackdrop.current != null
    BackdropContrastScope(modifier = modifier) {
        WallpaperGlass(shape = shape, enabled = hasBackdrop) {
        MaterialButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = if (!hasBackdrop) colors else ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
            elevation = null,
            border = if (LocalTransparentUi.current) null else border ?: if (hasBackdrop) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.26f else 0.12f)) else null,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )
        }
    }
}


@Suppress("UNUSED_PARAMETER")
@Composable
fun BackdropClickableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: androidx.compose.ui.unit.Dp = 0.dp,
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    BackdropContrastScope(modifier = modifier) {
        MaterialSurface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = if (LocalTransparentUi.current) null else border,
            interactionSource = interactionSource,
            content = content,
        )
    }
}
