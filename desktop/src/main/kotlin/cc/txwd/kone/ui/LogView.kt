package cc.txwd.kone.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Logs: the core's last lines, newest at the foot, in Consolas so the columns line up. */
@Composable
fun LogView(lines: List<String>) {
    val ink = LocalInk.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp), state = list) {
        items(lines) { line ->
            BasicText(line, Modifier.padding(vertical = 2.dp), style = TextStyle(color = ink.soft, fontFamily = Fonts.mono, fontSize = 13.sp))
        }
    }
}
