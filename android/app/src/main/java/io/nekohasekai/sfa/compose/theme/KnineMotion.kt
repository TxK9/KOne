package io.nekohasekai.sfa.compose.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntSize

/**
 * One motion for every settings and tools page. A page opening moves a short way along the
 * reading direction and fades, rather than sliding a whole screen across the wallpaper, which
 * stays still behind it. Sections unfold with a soft spring and settle without a bounce.
 */
internal object KnineMotion {
    /** Material's emphasized deceleration: quick to arrive, gentle to land. */
    private val Arrive = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    private const val SHIFT_DIVISOR = 10

    fun pageOpen(): EnterTransition =
        slideInHorizontally(tween(340, easing = Arrive)) { it / SHIFT_DIVISOR } +
            fadeIn(tween(240, delayMillis = 60, easing = Arrive))

    fun pageCover(): ExitTransition =
        slideOutHorizontally(tween(240, easing = FastOutLinearInEasing)) { -it / (SHIFT_DIVISOR * 2) } +
            fadeOut(tween(150, easing = FastOutLinearInEasing))

    fun pageReturn(): EnterTransition =
        slideInHorizontally(tween(340, easing = Arrive)) { -it / (SHIFT_DIVISOR * 2) } +
            fadeIn(tween(240, delayMillis = 60, easing = Arrive))

    fun pageClose(): ExitTransition =
        slideOutHorizontally(tween(240, easing = FastOutLinearInEasing)) { it / SHIFT_DIVISOR } +
            fadeOut(tween(150, easing = FastOutLinearInEasing))

    /** A section unfolding under its row: height on a spring, the content fading in as room opens. */
    fun reveal(): EnterTransition =
        expandVertically(spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)) +
            fadeIn(tween(220, delayMillis = 50))

    fun conceal(): ExitTransition =
        fadeOut(tween(120)) +
            shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium, visibilityThreshold = IntSize.VisibilityThreshold))
}
