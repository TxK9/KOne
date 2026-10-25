package cc.txwd.kone.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.compositionLocalOf

/**
 * How KOne moves: briefly and calmly, as ink settles on paper, and not at all when Windows is set
 * to show no animations (Settings → Accessibility → Visual effects → Animation effects), which
 * [LocalStill] carries. Every transition in the App takes its timing from here.
 */
object Motion {
    /** Fast at first and settling softly: things arrive rather than slide. */
    val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** A press answered, a word changed. */
    const val QUICK = 120

    /** A page, a panel, a list making room. */
    const val STANDARD = 220

    /** Something worth seeing happen once: a connection made. */
    const val CALM = 650

    /** [millis] long with [Settle], or at once when [still]. */
    fun <T> spec(still: Boolean, millis: Int = STANDARD, delay: Int = 0): FiniteAnimationSpec<T> =
        if (still) snap() else tween(millis, delay, Settle)

    /**
     * One page giving way to another: the new one rises a few pixels into place as the old one
     * fades, so the eye follows the change without the page seeming to travel.
     */
    fun page(still: Boolean): ContentTransform =
        if (still) {
            fadeIn(snap()) togetherWith fadeOut(snap())
        } else {
            (fadeIn(tween(STANDARD, 40, Settle)) + slideInVertically(tween(STANDARD + 60, 0, Settle)) { it / 60 }) togetherWith
                fadeOut(tween(QUICK, 0, Settle))
        }
}

/** Whether to keep still: Windows' Animation effects are off. The backdrop then rests too. */
val LocalStill = compositionLocalOf { false }
