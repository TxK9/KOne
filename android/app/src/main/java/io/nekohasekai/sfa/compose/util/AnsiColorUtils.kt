package io.nekohasekai.sfa.compose.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

object AnsiColorUtils {
    fun ansiToAnnotatedString(text: String): AnnotatedString {
        val parsed = AnsiSgr.parse(text)
        if (parsed.runs.isEmpty()) return AnnotatedString(parsed.text)
        return buildAnnotatedString {
            append(parsed.text)
            parsed.runs.forEach { run -> addStyle(run.style.toSpanStyle(), run.start, run.end) }
        }
    }

    fun stripAnsi(text: String): String = AnsiSgr.strip(text)

    private fun AnsiTextStyle.toSpanStyle() = SpanStyle(
        color = foreground?.let { Color(it) } ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}
