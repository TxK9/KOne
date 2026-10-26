package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField as NativeOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun BackdropOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val backdrop = LocalBackdrop.current
    val ink = if (backdrop != null) Color(backdrop.theme.ink) else MaterialTheme.colorScheme.onSurface
    val accent = if (backdrop != null) Color(backdrop.theme.accent) else MaterialTheme.colorScheme.primary
    // Inputs share the page material and keep one palette while editing or scrolling.
    // The whole field, typed text and hairline included, sits in the city's shade.
    WallpaperGlass(modifier = modifier.cityLine(), shape = shape) {
        CompositionLocalProvider(LocalBackdrop provides null) {
            NativeOutlinedTextField(
                value = value, onValueChange = onValueChange, enabled = enabled, readOnly = readOnly,
                textStyle = if (backdrop != null) textStyle.copy(color = if (enabled) ink else ink.copy(alpha = .62f), shadow = androidx.compose.ui.graphics.Shadow.None) else textStyle,
                label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
                visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource, shape = shape,
                colors = if (backdrop != null) colors.copy(
                    focusedTextColor = ink, unfocusedTextColor = ink, disabledTextColor = ink.copy(alpha = 0.38f), errorTextColor = ink,
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
                    cursorColor = accent, errorCursorColor = MaterialTheme.colorScheme.error,
                    // A field's edge is what makes it a field: it keeps a hairline even where panels drop away.
                    focusedIndicatorColor = accent, unfocusedIndicatorColor = ink.copy(alpha = 0.34f),
                    disabledIndicatorColor = ink.copy(alpha = 0.16f), errorIndicatorColor = MaterialTheme.colorScheme.error,
                    focusedLabelColor = accent, unfocusedLabelColor = ink, disabledLabelColor = ink.copy(alpha = 0.38f), errorLabelColor = MaterialTheme.colorScheme.error,
                    focusedPlaceholderColor = ink.copy(alpha = 0.72f), unfocusedPlaceholderColor = ink.copy(alpha = 0.72f),
                    disabledPlaceholderColor = ink.copy(alpha = 0.38f), errorPlaceholderColor = ink.copy(alpha = 0.72f),
                ) else colors,
            )
        }
    }
}

@Composable
fun BackdropClickableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onClick: (Int) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BackdropText(
        text = text,
        modifier = modifier.pointerInput(text, onClick) {
            detectTapGestures { position -> layout?.let { onClick(it.getOffsetForPosition(position)) } }
        },
        style = style, softWrap = softWrap, overflow = overflow, maxLines = maxLines,
        onTextLayout = { layout = it; onTextLayout(it) },
    )
}
