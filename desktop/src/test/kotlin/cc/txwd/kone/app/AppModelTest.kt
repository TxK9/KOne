package cc.txwd.kone.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals

class AppModelTest {
    @Test
    fun serviceLogBatchesKeepOnlyTheNewestFiveHundredInOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = AppModel(scope, Preferences(Files.createTempDirectory("kone-log-batch").toFile()))
            model.appendLogs((0 until 450).map { "INFO line $it" })
            model.appendLogs((450 until 600).map { "INFO line $it" })
            assertEquals((100 until 600).map { "INFO line $it" }, model.logs.toList())
            model.appendLogs(emptyList())
            assertEquals(500, model.logs.size)
            model.appendLogs((600 until 2000).map { "INFO line $it" })
            assertEquals((1500 until 2000).map { "INFO line $it" }, model.logs.toList())
            model.logs.clear()
            model.appendLogs(listOf("INFO restarted"))
            assertEquals(listOf("INFO restarted"), model.logs.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun telemetryTicksKeepNodeAndSubscriptionSnapshots() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = AppModel(scope, Preferences(Files.createTempDirectory("kone-telemetry").toFile()))
            val nodes = model.dashboard.nodes
            val sections = model.nodesPage
            repeat(60) { model.refreshTelemetry() }
            assertSame(nodes, model.dashboard.nodes)
            assertSame(sections, model.nodesPage)
            assertEquals(0.0, model.dashboard.downKbps)
            assertEquals(0L, model.dashboard.seconds)
        } finally {
            scope.cancel()
        }
    }

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
