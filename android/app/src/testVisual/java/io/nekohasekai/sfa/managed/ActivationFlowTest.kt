package io.nekohasekai.sfa.managed

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.activation.ActivationScreenContent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real activation content and claim transaction in a disposable navigation host, without platform binding. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class ActivationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = context.getString(id)
    private val endpoint = requireNotNull(ManagedEndpoint.parse("https://activation.example.invalid"))
    private fun snapshot() = ManagedSnapshot(endpoint.base, "Synthetic phone", false, -1, "", "", null,
        0, ManagedStatus.Unbound, null, false)
    private fun count(label: String) = compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size
    private fun key() = compose.onNodeWithText(text(R.string.kone_activation_key))
    private fun activateTwice() {
        val action = compose.onNodeWithText(text(R.string.kone_activation_start)).assertIsEnabled()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(action()); assertTrue(action()) }
    }

    private class Controlled(private val failFirst: Boolean = false) {
        val requests = AtomicInteger()
        val bindings = AtomicInteger()
        val scheduled = AtomicInteger()
        val release = List(2) { CountDownLatch(1) }
        val job = AtomicReference<Job>()
        suspend fun claim(endpoint: ManagedEndpoint, code: String) {
            assertEquals("https://activation.example.invalid", endpoint.base)
            assertEquals("synthetic-invite", code)
            job.set(currentCoroutineContext()[Job])
            ManagedSync.claimTransaction(request = {
                val attempt = requests.getAndIncrement()
                check(release[attempt].await(10, TimeUnit.SECONDS))
                if (failFirst && attempt == 0) throw ManagedException(ManagedFailure.InviteInvalid)
                ManagedClaim("synthetic-device", "synthetic-credential")
            }, removeProfile = {}, bind = { bindings.incrementAndGet() },
                schedule = { scheduled.incrementAndGet() }, synchronize = {})
        }
        fun close() = release.forEach { it.countDown() }
    }

    @Test fun duplicateActivationIsSingleFlightAndFailurePreservesFieldsForRetry() {
        val controlled = Controlled(failFirst = true)
        try {
            compose.setContent { MaterialTheme { ActivationScreenContent(snapshot(), null, controlled::claim) } }
            key().performTextInput("synthetic-invite")
            activateTwice()
            compose.waitUntil(10_000) { controlled.requests.get() == 1 }
            controlled.release[0].countDown()
            compose.waitUntil(10_000) { count(text(R.string.kone_key_invalid)) == 1 }
            key().assertTextContains("synthetic-invite")
            compose.onNodeWithText(text(R.string.kone_activation_service)).assertTextContains(endpoint.base)
            assertEquals(0, controlled.bindings.get())
            activateTwice()
            compose.waitUntil(10_000) { controlled.requests.get() == 2 }
            controlled.release[1].countDown()
            compose.waitUntil(10_000) { controlled.bindings.get() == 1 }
            compose.waitForIdle()
            assertEquals(2, controlled.requests.get())
            assertEquals(1, controlled.scheduled.get())
        } finally { controlled.close() }
    }

    @Test fun navigatingBackCancelsTheClaimAndReentryDoesNotRestoreTheInvite() {
        val controlled = Controlled()
        lateinit var navigation: NavHostController
        try {
            compose.setContent {
                MaterialTheme {
                    navigation = rememberNavController()
                    NavHost(navigation, startDestination = "home") {
                        composable("home") { Text("Synthetic home") }
                        composable("activation") { ActivationScreenContent(snapshot(), null, controlled::claim) }
                    }
                }
            }
            compose.runOnIdle { navigation.navigate("activation") }
            key().performTextInput("synthetic-invite")
            activateTwice()
            compose.waitUntil(10_000) { controlled.requests.get() == 1 }
            compose.runOnIdle { assertTrue(navigation.popBackStack()) }
            compose.waitForIdle()
            compose.waitUntil(10_000) { controlled.job.get()?.isCancelled == true }
            controlled.release[0].countDown()
            compose.waitUntil(10_000) { controlled.job.get()?.isCompleted == true }
            assertEquals(0, controlled.bindings.get())
            assertEquals(0, controlled.scheduled.get())
            compose.runOnIdle { navigation.navigate("activation") }
            assertEquals("", key().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithText(text(R.string.kone_activation_service)).assertTextContains(endpoint.base)
            key().performTextInput("synthetic-invite")
            activateTwice()
            compose.waitUntil(10_000) { controlled.requests.get() == 2 }
            controlled.release[1].countDown()
            compose.waitUntil(10_000) { controlled.bindings.get() == 1 }
            assertEquals(1, controlled.scheduled.get())
        } finally { controlled.close() }
    }

    @Test fun operationCancellationDoesNotBecomeAnActivationFailureMessage() {
        val calls = AtomicInteger()
        compose.setContent { MaterialTheme {
            ActivationScreenContent(snapshot(), null) { _, _ ->
                calls.incrementAndGet()
                throw CancellationException("Synthetic cancellation")
            }
        } }
        key().performTextInput("synthetic-invite")
        val action = compose.onNodeWithText(text(R.string.kone_activation_start)).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { action() }
        compose.waitUntil(10_000) { calls.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.kone_activation_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.kone_activation_start)).assertIsEnabled()
    }
}
