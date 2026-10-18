package io.nekohasekai.sfa.compose.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as NativeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** When set, labels get a soft glow in this colour instead of a card behind them. */
internal val LocalBackdropHalo = staticCompositionLocalOf<Color?> { null }

internal fun ordinaryBackdropInk(color: Color, parent: Color): Boolean =
    color == Color.Unspecified || (color.red == parent.red && color.green == parent.green && color.blue == parent.blue)

@Composable
fun BackdropText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    emphasized: Boolean = false,
    adaptive: Boolean = true,
) {
    AdaptiveLabel(modifier, color, style, emphasized, adaptive, onTextLayout) { finalModifier, ink, finalStyle, layout ->
        NativeText(
            text = text, modifier = finalModifier, color = ink, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
            textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
            onTextLayout = layout, style = finalStyle,
        )
    }
}

@Composable
fun BackdropText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    emphasized: Boolean = false,
    adaptive: Boolean = true,
) {
    val hasBackdrop = LocalBackdrop.current != null && adaptive
    AdaptiveLabel(modifier, color, style, emphasized, adaptive, onTextLayout) { finalModifier, ink, finalStyle, layout ->
        // Keep log/link annotations and text intact. ANSI foreground colors do
        // not override the wallpaper's settled, contrast-supported foreground.
        val readable = remember(text, ink, hasBackdrop) {
            if (hasBackdrop) buildAnnotatedString {
                append(text)
                addStyle(SpanStyle(color = ink, background = Color.Transparent, shadow = Shadow.None), 0, length)
            } else text
        }
        NativeText(
            text = readable, modifier = finalModifier, color = ink, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
            textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
            inlineContent = inlineContent, onTextLayout = { layout?.invoke(it) }, style = finalStyle,
        )
    }
}

@Composable
private fun AdaptiveLabel(
    modifier: Modifier,
    color: Color,
    style: TextStyle,
    emphasized: Boolean,
    adaptive: Boolean,
    onTextLayout: ((TextLayoutResult) -> Unit)?,
    content: @Composable (Modifier, Color, TextStyle, ((TextLayoutResult) -> Unit)?) -> Unit,
) {
    val backdrop = LocalBackdrop.current
    if (backdrop == null || !adaptive) {
        content(modifier, color, style, onTextLayout)
        return
    }
    val parentInk = LocalContentColor.current
    val requested = if (color == Color.Unspecified) parentInk else color
    val automatic = ordinaryBackdropInk(requested, MaterialTheme.colorScheme.onSurface)
    val selected = if (automatic) Color(if (emphasized) backdrop.theme.accent else backdrop.theme.ink) else requested
    val ink = Color(KninePalette.stableForeground(selected.copy(alpha = 1f).toArgb(), backdrop.theme, 5f)).copy(alpha = requested.alpha)
    // One committed wallpaper palette; no label bounds or pixel sampling can
    // change it when a list scrolls, a counter updates or a line wraps.
    content(
        modifier,
        ink,
        style.copy(
            shadow = LocalBackdropHalo.current?.let { Shadow(it, Offset.Zero, 22f) }
                ?: if (LocalTransparentUi.current) Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 3f) else Shadow.None,
        ),
        onTextLayout,
    )
}
