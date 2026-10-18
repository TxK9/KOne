package cc.txwd.kone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Logs: the core's last lines, newest at the foot, in Consolas so the columns line up; Copy puts
 * them on the clipboard (for sending to whoever helps), Clear starts afresh. Both only when there is something.
 */
@Composable
fun LogView(lines: List<String>, onCopy: () -> Unit = {}, onClear: () -> Unit = {}) {
    val ink = LocalInk.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
        if (lines.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onCopy), contentAlignment = Alignment.Center) { Label("Copy", 14.sp, weight = FontWeight.SemiBold) }
                Box(Modifier.height(34.dp).press(bleed = 8.dp, onClick = onClear), contentAlignment = Alignment.Center) { Label("Clear", 14.sp, ink.soft) }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(lines) { line ->
                BasicText(line, Modifier.padding(vertical = 2.dp), style = TextStyle(color = ink.soft, fontFamily = Fonts.mono, fontSize = 13.sp))
            }
        }
    }
}
