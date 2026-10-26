package cc.txwd.kone.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import cc.txwd.kone.i18n.Strings

/** Latin UI, serif seal monograms, and monospaced addresses. System fallbacks keep
 * English text available independently of the legacy CJK-only subset assets.
 */
@OptIn(ExperimentalTextApi::class)
object Fonts {
    private val manager = FontMgr.default

    private fun installed(vararg names: String): String? = names.firstOrNull { manager.matchFamily(it).count() > 0 }

    private fun system(name: String?): FontFamily =
        if (name == null) FontFamily.SansSerif else FontFamily(SystemFont(name, FontWeight.Normal), SystemFont(name, FontWeight.Bold))

    private val latin: FontFamily = system(installed("Segoe UI", "Arial", "DejaVu Sans", "Liberation Sans"))
    private val chinese: FontFamily = system(installed("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "WenQuanYi Micro Hei"))
    val ui: FontFamily get() = if (Strings.chinese) chinese else latin

    val mono: FontFamily = installed("Consolas", "SF Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono")
        ?.let { FontFamily(SystemFont(it, FontWeight.Normal)) } ?: FontFamily.Monospace

    private val serif = installed("Georgia", "Times New Roman", "DejaVu Serif", "Liberation Serif")

    /** Latin seals; original font assets and their license remain intact. */
    val seal: FontFamily get() = if (Strings.chinese) chinese else if (serif == null) FontFamily.Serif else system(serif)

    /** Matching faces for the painting's English monograms and inscriptions. */
    val sealTypeface: Typeface? = manager.matchFamilyStyle(serif, FontStyle.BOLD)
    val mottoTypeface: Typeface? = manager.matchFamilyStyle(serif, FontStyle.NORMAL)
}
