package cc.txwd.kone.ui

import cc.txwd.kone.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Something the palette can do: what it says, a few words beside it, and the doing. */
class Command(val title: String, val detail: String = "", val run: () -> Unit)

/**
 * Which commands fit what was typed, best first: every word typed must be found in the title or
 * the detail; a word that starts the title, or starts one of its words, counts for more. With
 * nothing typed, the commands as given, the most useful first.
 */
fun rank(commands: List<Command>, query: String, most: Int = 8): List<Command> {
    val words = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return commands.take(most)
    return commands.mapIndexedNotNull { index, command ->
        val title = command.title.lowercase()
        val text = "$title ${command.detail.lowercase()}"
        if (words.any { it !in text }) return@mapIndexedNotNull null
        val score = words.sumOf { word ->
            when {
                title.startsWith(word) -> 4
                Regex("""(^|[\s·:(])${Regex.escape(word)}""").containsMatchIn(title) -> 3
                word in title -> 2
                else -> 1
            }.toInt()
        }
        Triple(command, score, index)
    }.sortedWith(compareByDescending<Triple<Command, Int, Int>> { it.second }.thenBy { it.third }).take(most).map { it.first }
}

/**
 * The command palette (Ctrl+K): a field, and the commands that fit what is typed, kept up as one
 * types. Up and Down choose, Enter does it, Esc or a press outside closes. [commands] is asked
 * again as the text changes, so a typed site can become "Check example.com".
 */
@Composable
fun Palette(commands: (String) -> List<Command>, onClose: () -> Unit) {
    val ink = LocalInk.current
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(0) }
    val shown = rank(commands(query), query)
    val focus = remember { FocusRequester() }
    fun run(command: Command?) {
        command ?: return
        onClose()
        command.run()
    }
    Box(
        Modifier.fillMaxSize().background(ink.ink.copy(alpha = 0.18f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 96.dp).width(560.dp).clip(RoundedCornerShape(10.dp)).background(ink.paper)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(vertical = 10.dp),
        ) {
            BasicTextField(
                query,
                { query = it; chosen = 0 },
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(40.dp).focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onClose(); true }
                            Key.DirectionDown -> { if (shown.isNotEmpty()) chosen = (chosen + 1) % shown.size; true }
                            Key.DirectionUp -> { if (shown.isNotEmpty()) chosen = (chosen - 1 + shown.size) % shown.size; true }
                            Key.Enter, Key.NumPadEnter -> { run(shown.getOrNull(chosen)); true }
                            else -> false
                        }
                    }
                    .hairline(ink.hair, bottom = true),
                singleLine = true,
                textStyle = TextStyle(color = ink.ink, fontFamily = Fonts.ui, fontSize = 15.sp),
                cursorBrush = SolidColor(ink.ink),
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Label(tr("A command, a node, a page or a site"), 15.sp, ink.soft)
                        field()
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) {
                Label(tr("Nothing fits"), 13.sp, ink.soft, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            shown.forEachIndexed { index, command ->
                Row(
                    Modifier.fillMaxWidth().height(38.dp)
                        .background(if (index == chosen) ink.press else ink.paper)
                        .press { run(command) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Label(command.title, 14.sp, weight = if (index == chosen) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.weight(1f))
                    if (command.detail.isNotEmpty()) Label(command.detail, 12.sp, ink.soft)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                Label(tr("↑↓ choose · Enter do · Esc close"), 11.sp, ink.soft)
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
