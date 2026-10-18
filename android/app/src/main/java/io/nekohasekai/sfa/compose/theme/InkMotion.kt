package io.nekohasekai.sfa.compose.theme

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

@Composable
internal fun smoothReadableInk(target: Color, label: String): Color {
    val animation = remember { Animatable(target) }
    val samePolarity = KninePalette.canTweenInk(animation.value.toArgb(), target.toArgb())
    // Never interpolate dark text through a low-contrast grey on its way to light.
    // A polarity change is a single settled choice; same-polarity accents can ease.
    LaunchedEffect(target) {
        if (KninePalette.canTweenInk(animation.value.toArgb(), target.toArgb())) {
            animation.animateTo(target, tween(180))
        } else {
            animation.snapTo(target)
        }
    }
    // Return the readable target immediately, including the frame before the
    // effect runs, instead of allowing an asynchronous spec update to tween it.
    return if (samePolarity) animation.value else target
}
