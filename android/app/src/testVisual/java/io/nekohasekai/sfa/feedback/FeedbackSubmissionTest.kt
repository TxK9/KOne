package io.nekohasekai.sfa.feedback

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.feedback.FeedbackScreenContent
import io.nekohasekai.sfa.compose.topbar.LocalTopBarController
import io.nekohasekai.sfa.compose.topbar.TopBarController
import io.nekohasekai.sfa.managed.ManagedEndpoint
import io.nekohasekai.sfa.managed.ManagedSnapshot
import io.nekohasekai.sfa.managed.ManagedStatus
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual screen callback, draft and API; only binding acquisition and HTTP transport are synthetic. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class FeedbackSubmissionTest {
    @get:Rule val compose = createComposeRule()

    private class Pending(url: URL, private val code: Int) : HttpURLConnection(url) {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val body = ByteArrayOutputStream()
        override fun getOutputStream() = body
        override fun getResponseCode(): Int {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) throw SocketTimeoutException("Synthetic response was not released")
            return code
        }
        override fun getInputStream() = "{\"number\":\"FB-202611020001\"}".byteInputStream()
        override fun getErrorStream() = "{\"error\":\"Synthetic service failure\"}".byteInputStream()
        override fun connect() = Unit
        override fun disconnect() { release.countDown() }
        override fun usingProxy() = false
    }

    @Test fun immediateDuplicateCallbacksSendOnceAndFailureCanRetryTheSavedPayload() {
        val context = RuntimeEnvironment.getApplication()
        val endpoint = requireNotNull(ManagedEndpoint.parse("https://feedback.example.invalid"))
        val credential = "synthetic-feedback-credential"
        val draft = FeedbackDraft(context, endpoint.base, credential)
        draft.clear()
        draft.clearReceipt()
        draft.save("other", "Synthetic connection failure after node change")
        val attempts = CopyOnWriteArrayList<Pending>()
        val api = FeedbackApi(endpoint, credential, requestTimeoutMillis = 30_000) { url ->
            Pending(url, if (attempts.isEmpty()) 503 else 201).also { attempts.add(it) }
        }
        val snapshot = ManagedSnapshot(endpoint.base, "Synthetic phone", true, -1, "", "", null,
            0, ManagedStatus.Synced, null, false)
        fun nodes(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes()
        fun submitTwice() {
            val action = compose.onNodeWithText(context.getString(R.string.kone_feedback_send))
                .assertIsEnabled().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            // Invoke the same real callback twice before recomposition can disable its row.
            compose.runOnIdle { assertTrue(action()); assertTrue(action()) }
        }
        try {
            compose.setContent {
                val topBar = remember { TopBarController(mutableStateOf(emptyList())) }
                CompositionLocalProvider(LocalTopBarController provides topBar) {
                    MaterialTheme {
                        FeedbackScreenContent(remember { NavController(context) }, snapshot) {
                            Triple(FeedbackDraft.bindingKey(endpoint.base, credential), draft, api)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { nodes(context.getString(R.string.kone_feedback_send)).isNotEmpty() }
            submitTwice()
            compose.waitUntil(10_000) { attempts.size == 1 && attempts[0].entered.count == 0L }
            compose.onNodeWithText(context.getString(R.string.kone_feedback_sending)).assertIsNotEnabled()
            assertEquals(1, attempts.size)
            assertEquals("POST", attempts[0].requestMethod)
            assertEquals("Bearer $credential", attempts[0].getRequestProperty("Authorization"))
            val first = JSONObject(attempts[0].body.toString(Charsets.UTF_8))
            attempts[0].release.countDown()
            compose.waitUntil(10_000) { nodes(context.getString(R.string.kone_feedback_not_sent)).isNotEmpty() }
            assertEquals("Synthetic connection failure after node change", draft.description)
            assertNull(draft.receipt)
            submitTwice()
            compose.waitUntil(10_000) { attempts.size == 2 && attempts[1].entered.count == 0L }
            compose.onNodeWithText(context.getString(R.string.kone_feedback_sending)).assertIsNotEnabled()
            assertEquals(2, attempts.size)
            val retry = JSONObject(attempts[1].body.toString(Charsets.UTF_8))
            assertEquals(first.getString("requestId"), retry.getString("requestId"))
            assertEquals(first.toString(), retry.toString())
            attempts[1].release.countDown()
            compose.waitUntil(10_000) { draft.receipt?.first == "FB-202611020001" }
            compose.waitForIdle()
            compose.onNodeWithText("FB-202611020001").assertIsDisplayed()
            assertEquals(2, attempts.size)
            assertEquals("", draft.description)
            assertEquals("FB-202611020001", draft.receipt?.first)
        } finally {
            attempts.forEach { it.release.countDown() }
            draft.clear()
            draft.clearReceipt()
        }
    }

    @Test fun replacingTheBindingDoesNotLetTheOldCompletionOverwriteTheNewForm() {
        val context = RuntimeEnvironment.getApplication()
        val endpoint = requireNotNull(ManagedEndpoint.parse("https://feedback.example.invalid"))
        val oldDraft = FeedbackDraft(context, endpoint.base, "synthetic-old-credential")
        val newDraft = FeedbackDraft(context, endpoint.base, "synthetic-new-credential")
        for (draft in listOf(oldDraft, newDraft)) { draft.clear(); draft.clearReceipt() }
        oldDraft.save("other", "Synthetic original account draft")
        newDraft.save("other", "Synthetic replacement account draft")
        val attempts = CopyOnWriteArrayList<Pending>()
        val newRequests = AtomicInteger()
        val oldApi = FeedbackApi(endpoint, "synthetic-old-credential") { url -> Pending(url, 201).also(attempts::add) }
        val newApi = FeedbackApi(endpoint, "synthetic-new-credential") { _ ->
            newRequests.incrementAndGet()
            error("Replacement binding has not submitted")
        }
        var binding by mutableStateOf(Triple("synthetic-old-binding", oldDraft, oldApi))
        var snapshot by mutableStateOf(ManagedSnapshot(endpoint.base, "Old phone", true, -1, "", "", null,
            0, ManagedStatus.Synced, null, false))
        try {
            compose.setContent {
                val topBar = remember { TopBarController(mutableStateOf(emptyList())) }
                CompositionLocalProvider(LocalTopBarController provides topBar) {
                    MaterialTheme { FeedbackScreenContent(remember { NavController(context) }, snapshot) { binding } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Send")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Send").performClick()
            compose.waitUntil(10_000) { attempts.size == 1 && attempts[0].entered.count == 0L }
            compose.runOnIdle {
                binding = Triple("synthetic-new-binding", newDraft, newApi)
                snapshot = snapshot.copy(deviceName = "Replacement phone")
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(newDraft.description)).fetchSemanticsNodes().isNotEmpty()
            }
            attempts[0].release.countDown()
            compose.waitUntil(10_000) { oldDraft.receipt != null }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.kone_feedback_description)).assertTextContains(newDraft.description)
            compose.onNodeWithText("Send").assertIsEnabled()
            compose.onNodeWithText("FB-202611020001").assertDoesNotExist()
            assertNull(newDraft.receipt)
            assertEquals("Synthetic replacement account draft", newDraft.description)
            assertEquals(0, newRequests.get())
            assertEquals(1, attempts.size)
        } finally {
            attempts.forEach { it.release.countDown() }
            for (draft in listOf(oldDraft, newDraft)) { draft.clear(); draft.clearReceipt() }
        }
    }

    @Test fun navigatingBackPreservesNewerEditsWhenTheOldSubmissionIsAcknowledged() {
        val context = RuntimeEnvironment.getApplication()
        val endpoint = requireNotNull(ManagedEndpoint.parse("https://feedback.example.invalid"))
        val draft = FeedbackDraft(context, endpoint.base, "synthetic-navigation-credential")
        draft.clear(); draft.clearReceipt(); draft.save("other", "Synthetic original report")
        val attempts = CopyOnWriteArrayList<Pending>()
        val api = FeedbackApi(endpoint, "synthetic-navigation-credential") { url -> Pending(url, 201).also(attempts::add) }
        val snapshot = ManagedSnapshot(endpoint.base, "Synthetic phone", true, -1, "", "", null,
            0, ManagedStatus.Synced, null, false)
        val disposed = AtomicBoolean()
        lateinit var navigation: NavHostController
        try {
            compose.setContent {
                val topBar = remember { TopBarController(mutableStateOf(emptyList())) }
                CompositionLocalProvider(LocalTopBarController provides topBar) {
                    MaterialTheme {
                        navigation = rememberNavController()
                        NavHost(navigation, startDestination = "home") {
                            composable("home") { androidx.compose.material3.Text("Synthetic home") }
                            composable("feedback") {
                                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                                FeedbackScreenContent(navigation, snapshot) { Triple("synthetic-navigation", draft, api) }
                            }
                        }
                    }
                }
            }
            compose.runOnIdle { navigation.navigate("feedback") }
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Send")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Send").performClick()
            compose.waitUntil(10_000) { attempts.size == 1 && attempts[0].entered.count == 0L }
            compose.runOnIdle { assertTrue(navigation.popBackStack()) }
            compose.waitForIdle()
            assertTrue(disposed.get())
            draft.save("other", "Synthetic newer edit after leaving")
            attempts[0].release.countDown()
            compose.waitUntil(10_000) { draft.receipt != null }
            assertEquals("Synthetic newer edit after leaving", draft.description)
            compose.runOnIdle { navigation.navigate("feedback") }
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("FB-202611020001")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("FB-202611020001").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.kone_feedback_new)).performClick()
            compose.onNodeWithText(context.getString(R.string.kone_feedback_description))
                .assertTextContains("Synthetic newer edit after leaving")
            compose.onNodeWithText("Send").assertIsEnabled()
            assertEquals(1, attempts.size)
        } finally {
            attempts.forEach { it.release.countDown() }
            draft.clear(); draft.clearReceipt()
        }
    }
}
