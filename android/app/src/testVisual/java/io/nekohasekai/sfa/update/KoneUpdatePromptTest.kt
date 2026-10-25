package io.nekohasekai.sfa.update

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.security.MessageDigest
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35])
class KoneUpdatePromptTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("kone_app_updates", Context.MODE_PRIVATE)
    private val data = "test package".toByteArray()
    private val hash = KonePackageChecks.hex(MessageDigest.getInstance("SHA-256").digest(data))

    @Before fun reset() {
        prefs.edit().clear().commit()
        File(app.cacheDir, "kone-updates").deleteRecursively()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val mutable = org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<KoneUpdateStatus>>(KoneUpdates, "mutableState")
        mutable.value = KoneUpdateStatus()
    }

    private fun stage(offset: Int = 1): Pair<KoneRelease, File> {
        val code = BuildConfig.VERSION_CODE + offset
        val release = KoneRelease(app.packageName, code, "1.0.0",
            URI(BuildConfig.KONE_UPDATE_FEED).resolve("/downloads/KOne-update-test.apk").toString(),
            hash, data.size.toLong(), code, 24, true, false, "Test")
        val file = File(app.cacheDir, "kone-updates/$code-${hash.take(16)}.apk")
        file.parentFile!!.mkdirs()
        file.writeBytes(data)
        prefs.edit().putString("release", release.toJson()).putString("verified_sha", hash).commit()
        return release to file
    }

    private suspend fun awaitUpdateTasks() = withTimeout(3_000) {
        val scope = org.robolectric.util.ReflectionHelpers.getField<CoroutineScope>(KoneUpdates, "scope")
        while (true) {
            val children = scope.coroutineContext[Job]!!.children.toList()
            if (children.isEmpty()) break
            children.joinAll()
        }
    }

    @Test fun importantUpdateAwaitingSystemConfirmationIsOfferedAndCanBeDeferred() = runBlocking {
        stage()
        // ShadowSession.commit immediately sends its own completion broadcast. Create
        // a real open session and deliver exactly the pending-system-action result we
        // are exercising, rather than racing that unrelated synthetic completion.
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(data.size.toLong())
        }
        val sessionId = app.packageManager.packageInstaller.createSession(params)
        prefs.edit().putInt("session", sessionId).commit()
        KoneUpdates.installResult(app, Intent(KonePackageInstaller.ACTION).putExtra(KonePackageInstaller.SESSION, sessionId)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(KoneUpdatePhase.Confirmation, KoneUpdates.state.value.phase)
        assertTrue(KoneUpdates.state.value.important)
        compose.setContent {
            MaterialTheme {
                Text("The page remains usable")
                KoneUpdatePrompt()
            }
        }
        compose.runOnIdle { KoneUpdates.offerForeground(app) }
        awaitUpdateTasks()
        compose.waitForIdle()
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(sessionId))
        assertEquals(KoneUpdatePhase.Confirmation, KoneUpdates.state.value.phase)
        // The status is a row; what to do about it is one tap away.
        compose.onNodeWithText("Confirm installation in the system dialog.").assertDoesNotExist()
        compose.onNodeWithText("Needs confirmation").performClick()
        compose.onNodeWithText("Confirm installation in the system dialog.").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("The page remains usable").assertIsDisplayed()
        compose.onNodeWithText("Important update").assertDoesNotExist()
        assertEquals(0, KoneUpdates.state.value.showNonce)
        assertTrue(KoneUpdates.state.value.verified)
    }

    @Test fun deferredReleaseDoesNotReappearAfterPromptRecreationButManualOpenAndNewReleaseWork() = runBlocking {
        stage()
        val recreation = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                Text("The page remains usable")
                key(recreation.intValue) { KoneUpdatePrompt() }
            }
        }
        compose.runOnIdle { KoneUpdates.onResume(app) }
        awaitUpdateTasks()
        compose.runOnIdle { KoneUpdates.offerForeground(app) }
        awaitUpdateTasks()
        compose.waitForIdle()
        compose.onNodeWithText("Important update").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()

        compose.runOnIdle { recreation.intValue++ }
        compose.waitForIdle()
        compose.runOnIdle { KoneUpdates.offerForeground(app) }
        awaitUpdateTasks()
        compose.waitForIdle()
        compose.onNodeWithText("Important update").assertDoesNotExist()
        assertEquals(0, KoneUpdates.state.value.showNonce)

        compose.runOnIdle { KoneUpdates.requestShow(app) }
        awaitUpdateTasks()
        compose.waitForIdle()
        compose.onNodeWithText("Important update").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()

        stage(offset = 2)
        compose.runOnIdle { KoneUpdates.onResume(app) }
        awaitUpdateTasks()
        compose.runOnIdle { KoneUpdates.offerForeground(app) }
        awaitUpdateTasks()
        compose.waitForIdle()
        compose.onNodeWithText("Important update").assertIsDisplayed()
        assertEquals(BuildConfig.VERSION_CODE + 2, KoneUpdates.state.value.release?.versionCode)
    }

    @Test fun deniedSystemNotificationsDoNotPreventTheForegroundUpdatePromptOrStartTheVpn() = runBlocking {
        stage()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent {
            MaterialTheme {
                Text("The page remains usable")
                KoneUpdatePrompt()
            }
        }
        compose.runOnIdle { KoneUpdates.onResume(app) }
        awaitUpdateTasks()
        compose.runOnIdle {
            KoneUpdates.onNotificationPermissionResult(app)
            KoneUpdates.offerForeground(app)
        }
        awaitUpdateTasks()
        compose.waitForIdle()
        compose.onNodeWithText("Important update").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("The page remains usable").assertIsDisplayed()
        assertTrue(KoneUpdates.state.value.verified)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(shadowOf(app).broadcastIntents.any { it.action?.contains("SERVICE_CLOSE") == true })
    }

    private fun release(notes: String = "") = KoneRelease(app.packageName, BuildConfig.VERSION_CODE + 1, "1.0.1",
        URI(BuildConfig.KONE_UPDATE_FEED).resolve("/downloads/KOne-update-test.apk").toString(),
        hash, 12L * 1_048_576, BuildConfig.VERSION_CODE + 1, 24, true, false, notes)

    @Test fun everyPhaseIsAShortRowWithItsFigureOnTheRight() {
        val release = release()
        assertEquals(UpdateLine("Available", "12 MB"), updateLine(KoneUpdateStatus(release), release))
        assertEquals(UpdateLine("Downloading", "50%"), updateLine(KoneUpdateStatus(release, KoneUpdatePhase.Downloading, progress = 0.5f), release))
        assertEquals(UpdateLine("Waiting for Wi-Fi", "12 MB"), updateLine(KoneUpdateStatus(release, KoneUpdatePhase.WaitingForWifi), release))
        assertEquals("Needs permission", updateLine(KoneUpdateStatus(release, KoneUpdatePhase.Ready, verified = true), release).title)
        assertEquals(UpdateLine("Ready to install"), updateLine(KoneUpdateStatus(release, KoneUpdatePhase.Ready, verified = true, permissionGranted = true), release))
        val failed = updateLine(KoneUpdateStatus(release, KoneUpdatePhase.Failed, message = "Update download timed out. Try again."), release)
        assertEquals(NodeWeather.Cut, failed.weather)
        assertEquals("Update download timed out. Try again.", failed.detail)
    }

    @Test fun aFailureKeepsItsReasonAndTheNotesOneTapAway() {
        val release = release(notes = "Node switching keeps the open page.")
        val line = updateLine(KoneUpdateStatus(release, KoneUpdatePhase.Failed, message = "Installation incomplete. Try again."), release)
        compose.setContent {
            MaterialTheme {
                // The dialog's width, with its 24 dp margin for the text.
                Surface { Box(Modifier.width(312.dp).padding(horizontal = 24.dp)) { UpdateRows(release, line) } }
            }
        }
        compose.onNodeWithText("KOne 1.0.1").assertIsDisplayed()
        compose.onNodeWithText("Installation incomplete. Try again.").assertDoesNotExist()
        compose.onNodeWithText("Update failed").performClick()
        compose.onNodeWithText("Installation incomplete. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Node switching keeps the open page.").assertDoesNotExist()
        compose.onNodeWithText("What's new").performClick()
        compose.onNodeWithText("Node switching keeps the open page.").assertIsDisplayed()
        val output = File(System.getProperty("knine.previewDir"), "update-failed.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
