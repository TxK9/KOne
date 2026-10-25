package cc.txwd.kone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The page's paper and ink, as docs/windows/preview.html sets them in its CSS variables. */
data class Ink(
    val dark: Boolean,
    val paper: Color,
    val panel: Color,
    val menu: Color,
    val ink: Color,
    val soft: Color,
    val hair: Color,
    val press: Color,
    /** The mist that rises over the foot of the painting and the veil behind the key form. */
    val veil: Color,
) {
    companion object {
        val Light = Ink(
            dark = false,
            paper = Color(0xFFF0F2F5),
            panel = Color(0xFFF7F8FA),
            menu = Color(0xFFFBFCFD),
            ink = Color(0xFF1C2622),
            soft = Color(0xFF4A5550),
            hair = Color(0x1F1C2622),
            press = Color(0x121C2622),
            veil = Color(0xFFF0F2F5),
        )
        val Dark = Ink(
            dark = true,
            paper = Color(0xFF181A1F),
            panel = Color(0xFF1E2126),
            menu = Color(0xFF23262C),
            ink = Color(0xFFD8E2DE),
            soft = Color(0xFFA9B4B0),
            hair = Color(0x21D8E2DE),
            press = Color(0x14D8E2DE),
            veil = Color(0xFF181A1F),
        )
    }
}

val LocalInk = staticCompositionLocalOf { Ink.Light }

/**
 * The paper and ink for [dark] or light. A change of theme washes over the page in a moment
 * rather than flashing; at once when [still].
 */
@Composable
fun KoneTheme(dark: Boolean, still: Boolean = false, content: @Composable () -> Unit) {
    val target = if (dark) Ink.Dark else Ink.Light
    val spec = Motion.spec<Color>(still, Motion.STANDARD + 180)
    @Composable
    fun Color.eased(label: String) = animateColorAsState(this, spec, label = label).value
    val ink = Ink(
        dark = dark,
        paper = target.paper.eased("paper"),
        panel = target.panel.eased("panel"),
        menu = target.menu.eased("menu"),
        ink = target.ink.eased("ink"),
        soft = target.soft.eased("soft"),
        hair = target.hair.eased("hair"),
        press = target.press.eased("press"),
        veil = target.veil.eased("veil"),
    )
    CompositionLocalProvider(LocalInk provides ink, content = content)
}
