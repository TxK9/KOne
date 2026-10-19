package cc.txwd.kone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AppModelTest {
    /**
     * The App is made as Main makes it, with every delegate and the work it starts at once. A
     * property used while starting but declared after the start once left one delegate unmade,
     * and KOne stopped before it showed a window; the screen tests never make the App, so only
     * this finds that.
     */
    @Test
    fun theAppStarts() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = AppModel(scope, Preferences(Files.createTempDirectory("kone-preferences").toFile()))
            assertEquals(Screen.Key, model.screen)
            assertEquals(null, model.dashboard.signIn)
            assertEquals(emptyList(), model.nodesPage.sections.flatMap { it.nodes })
        } finally {
            scope.cancel()
        }
    }
}
