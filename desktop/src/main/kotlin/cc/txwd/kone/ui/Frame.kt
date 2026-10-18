package cc.txwd.kone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The window as the preview draws it: a hairline round the paper, the title bar, then the page. */
@Composable
fun Frame(titleBar: @Composable () -> Unit, content: @Composable () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.paper).border(1.dp, ink.hair)) {
        titleBar()
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A page of the App with the rail beside it. */
@Composable
fun WithRail(page: Page, onPage: (Page) -> Unit, pages: List<Page> = Page.entries, marked: Set<Page> = emptySet(), content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Rail(page, onPage, pages, marked)
        Box(Modifier.weight(1f)) { content() }
    }
}
