package io.nekohasekai.sfa.compose.screen.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherSeal
import io.nekohasekai.sfa.compose.theme.KninePalette
import io.nekohasekai.sfa.compose.theme.LocalBackdrop
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/*
 * The owner's web tools (the console, the router) share one frame: paper and ink, a back arrow and
 * a serif title on one line, a hairline, a thin ink line while a page loads, and, when the page
 * cannot be had, a seal with the reason and one way to try again in its place. With the living
 * city chosen, the frame sits on the city in the app's own ink, and only the page keeps its paper.
 */

/** The paper a web tool sits on before its page says otherwise. */
internal fun webToolPaper(dark: Boolean): Color = Color(if (dark) 0xFF181A1F.toInt() else 0xFFF0F2F5.toInt())

/** The ink a web tool writes in before its page says otherwise. */
internal fun webToolInk(dark: Boolean): Color = Color(if (dark) KninePalette.LIGHT_INK else KninePalette.DARK_INK)

/** The theme's colours remapped to one [paper] and one [ink], so every native control matches the page. */
@Composable
internal fun WebToolTheme(paper: Color, ink: Color, content: @Composable () -> Unit) {
    if (LocalBackdrop.current != null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, paper, ink) {
        base.copy(
            surface = paper, background = paper, onSurface = ink, onBackground = ink,
            onSurfaceVariant = ink.copy(alpha = .65f), primary = ink, onPrimary = paper,
            outline = ink.copy(alpha = .38f), outlineVariant = ink.copy(alpha = .14f),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Back, the serif title stepping down rather than wrapping, the [actions]; then a hairline. */
@Composable
internal fun WebToolBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.content_description_back), tint = ink)
        }
        // One line between the buttons: the title steps down rather than wrapping.
        BoxWithConstraints(Modifier.weight(1f)) {
            val measurer = rememberTextMeasurer()
            val room = constraints.maxWidth
            val size = remember(title, room) {
                var sp = 22f
                while (sp > 15f && measurer.measure(title, TextStyle(fontFamily = FontFamily.Serif, fontSize = sp.sp), maxLines = 1, softWrap = false).size.width > room) sp -= 0.5f
                sp
            }
            Text(title, fontFamily = FontFamily.Serif, fontSize = size.sp, color = ink, maxLines = 1, softWrap = false)
        }
        actions()
    }
    HorizontalDivider(color = ink.copy(alpha = .10f))
}

/** The thin ink line while a page loads. */
@Composable
internal fun WebToolProgress(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier.fillMaxWidth().height(2.dp),
        color = MaterialTheme.colorScheme.onSurface,
        trackColor = MaterialTheme.colorScheme.surface,
    )
}

/** In the page's place when it cannot be had: the storm seal, the reason, and one way to try again. */
@Composable
internal fun WebToolProblem(title: String, hint: String?, onRetry: () -> Unit, modifier: Modifier = Modifier, secondary: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WeatherSeal(NodeWeather.Cut, size = 48.dp)
        Spacer(Modifier.height(24.dp))
        Text(title, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        val retry: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(stringResource(R.string.kone_console_retry), Modifier.padding(start = 8.dp))
        }
        if (LocalBackdrop.current != null) {
            // Over the city there is no paper to cut a filled button out of: an ink outline, like KOne's other actions.
            val ink = MaterialTheme.colorScheme.onSurface
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ink.copy(alpha = .5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                content = retry,
            )
        } else {
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
                content = retry,
            )
        }
        secondary?.invoke()
    }
}
