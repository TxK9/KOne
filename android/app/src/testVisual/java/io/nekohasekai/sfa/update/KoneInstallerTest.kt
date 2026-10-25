package io.nekohasekai.sfa.update

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KoneInstallerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("kone_app_updates", Context.MODE_PRIVATE)
    private val payload = "test package bytes".toByteArray()
    private val hash = KonePackageChecks.hex(MessageDigest.getInstance("SHA-256").digest(payload))

    @Before fun reset() {
        prefs.edit().clear().commit()
        File(app.cacheDir, "kone-updates").deleteRecursively()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    private fun release(offset: Int = 1) = KoneRelease(app.packageName, BuildConfig.VERSION_CODE + offset, "1.0.0",
        URI(BuildConfig.KONE_UPDATE_FEED).resolve("/downloads/KOne-update-test.apk").toString(), hash, payload.size.toLong(),
        BuildConfig.VERSION_CODE + offset, 24, true, false, "Test")

    private fun stage(release: KoneRelease = release()): File {
        val file = File(app.cacheDir, "kone-updates/${release.versionCode}-${hash.take(16)}.apk")
        file.parentFile!!.mkdirs()
        file.writeBytes(payload)
        prefs.edit().putString("release", release.toJson()).putString("verified_sha", hash).commit()
        return file
    }

    /** Wait for asynchronous public API calls without starting a network check. */
    private suspend fun awaitUpdateTasks() = withTimeout(3_000) {
        val scope = org.robolectric.util.ReflectionHelpers.getField<CoroutineScope>(KoneUpdates, "scope")
        while (true) {
            val children = scope.coroutineContext[Job]!!.children.toList()
            if (children.isEmpty()) break
            children.joinAll()
        }
    }

    @Test fun commitWaitsForTheSystemAndDoesNotStopTheVpn() {
        var id = -1
        KonePackageInstaller.install(app, stage(), release().versionCode) { id = it }
        assertTrue(id >= 0)
        val session = app.packageManager.packageInstaller.getSessionInfo(id)
        assertNotNull(session)
        assertEquals(app.packageName, session!!.appPackageName)
        assertFalse(shadowOf(app).broadcastIntents.any { it.action?.contains("SERVICE_CLOSE") == true })
        assertFalse(prefs.getBoolean("installed", false))
    }

    @Test fun failedStagingAbandonsItsSession() {
        val missing = File(app.cacheDir, "absent.apk")
        assertTrue(runCatching { KonePackageInstaller.install(app, missing, release().versionCode) {} }.isFailure)
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun importantMetadataAloneCannotClaimAVerifiedUpdate() {
        prefs.edit().putString("release", release().toJson()).commit()
        assertFalse(KoneUpdates.hasImportantUpdate(app))
        val file = stage()
        assertTrue(KoneUpdates.hasImportantUpdate(app))
        file.delete()
        assertFalse(KoneUpdates.hasImportantUpdate(app))
    }

    @Test fun backgroundUserConfirmationIsNotReportedAsSuccessOrLaunchedOverOtherApps() = runBlocking {
        val file = stage()
        var id = -1
        KonePackageInstaller.install(app, file, release().versionCode) {
            id = it
            prefs.edit().putInt("session", it).commit()
        }
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val result = Intent(KonePackageInstaller.ACTION).putExtra(KonePackageInstaller.SESSION, id)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_VIEW))
        KoneUpdates.installResult(app, result)
        assertEquals(KoneUpdatePhase.Confirmation, KoneUpdates.state.value.phase)
        assertTrue(prefs.getBoolean("confirmation", false))
        assertNull(shadowOf(app).nextStartedActivity)
        val notifications = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications
        assertEquals(1, notifications.size)
        val notification = notifications.single()
        assertNotNull(notification.channelId)
        assertTrue(notification.contentIntent.isActivity)
        assertTrue(notification.contentIntent.isImmutable)
        val target = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, target.component?.className)
        assertTrue(target.getBooleanExtra(KoneUpdates.SHOW_UPDATE, false))
        assertTrue(target.extras!!.keySet().none { key ->
            listOf("token", "credential", "password", "url").any { key.contains(it, ignoreCase = true) }
        })
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun notificationOpenRequestedBeforeCachedStateRestoreIsNotLost() = runBlocking {
        stage()
        // A notification can launch a fresh process before start() has restored its state.
        val mutable = org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<KoneUpdateStatus>>(KoneUpdates, "mutableState")
        mutable.value = KoneUpdateStatus()
        KoneUpdates.requestShow(app)
        awaitUpdateTasks()
        assertTrue(KoneUpdates.state.value.showNonce > 0)
        assertEquals(release().versionCode, KoneUpdates.state.value.release?.versionCode)
        assertTrue(KoneUpdates.state.value.verified)
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun laterClearsAnEarlierPendingOpenButANewExplicitRequestStillWorks() = runBlocking {
        stage()
        KoneUpdates.requestShow(app)
        awaitUpdateTasks()
        assertTrue(KoneUpdates.state.value.showNonce > 0)

        // Represent an older tap still waiting to be consumed, without a timing race.
        prefs.edit().putBoolean("pending_open", true).commit()
        KoneUpdates.dismissPrompt(app)
        assertFalse(prefs.getBoolean("pending_open", true))
        assertEquals(0, KoneUpdates.state.value.showNonce)
        KoneUpdates.onResume(app)
        awaitUpdateTasks()
        KoneUpdates.offerForeground(app)
        assertEquals(0, KoneUpdates.state.value.showNonce)

        KoneUpdates.requestShow(app)
        awaitUpdateTasks()
        assertTrue(KoneUpdates.state.value.showNonce > 0)
        assertFalse(prefs.getBoolean("pending_open", true))
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun deniedNotificationPermissionKeepsTheUpdateAndNeverStartsTheVpn() = runBlocking {
        stage()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().putInt("session", 44).commit()
        KoneUpdates.installResult(app, Intent(KonePackageInstaller.ACTION).putExtra(KonePackageInstaller.SESSION, 44)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertTrue(KoneUpdates.needsNotificationPermission(app))
        KoneUpdates.markNotificationPermissionRequested(app)
        KoneUpdates.onNotificationPermissionResult(app)
        KoneUpdates.requestShow(app)
        awaitUpdateTasks()
        assertTrue(KoneUpdates.state.value.showNonce > 0)
        assertNotNull(KoneUpdates.state.value.release)
        assertTrue(KoneUpdates.state.value.verified)
        assertTrue(shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(shadowOf(app).broadcastIntents.any { it.action?.contains("SERVICE_CLOSE") == true })
    }

    @Test fun readyNotificationIsNotRepostedAfterDismissalButANewerReleaseGetsOne() = runBlocking {
        stage()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        KoneUpdates.onResume(app)
        awaitUpdateTasks()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        val first = shadowOf(manager).allNotifications.single()
        assertNotEquals(0, first.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE)
        manager.cancelAll() // Simulate dismissing the system notification.
        KoneUpdates.onResume(app)
        awaitUpdateTasks()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        stage(release(offset = 2))
        KoneUpdates.onResume(app)
        awaitUpdateTasks()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(release(offset = 2).versionCode, KoneUpdates.state.value.release?.versionCode)
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun deniedNotificationIsOfferedOnceAfterPermissionIsGrantedWithoutStartingAService() = runBlocking {
        stage()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        KoneUpdates.onResume(app)
        awaitUpdateTasks()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        KoneUpdates.onNotificationPermissionResult(app)
        awaitUpdateTasks()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun corruptCachedPackageLosesItsVerifiedMarkerAndCanBeDownloadedAgain() = runBlocking {
        val file = stage()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        prefs.edit().putInt("session", 30).commit()
        KoneUpdates.installResult(app, Intent(KonePackageInstaller.ACTION).putExtra(KonePackageInstaller.SESSION, 30)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
        assertTrue(KoneUpdates.state.value.verified)
        file.writeBytes(payload.copyOf().apply { this[0] = 0 })
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        KoneUpdates.updateNow(app)
        withTimeout(3_000) { while (KoneUpdates.state.value.verified) delay(10) }
        assertFalse(KoneUpdates.hasImportantUpdate(app))
        assertNull(prefs.getString("verified_sha", null))
        assertFalse(file.exists())
    }

    @Test fun installationCallbackDoesNotWaitBehindADownload() = runBlocking {
        stage()
        prefs.edit().putInt("session", 50).commit()
        val networkLock = org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.sync.Mutex>(KoneUpdates, "lock")
        networkLock.lock()
        try {
            withTimeout(1_000) {
                KoneUpdates.installResult(app, Intent(KonePackageInstaller.ACTION).putExtra(KonePackageInstaller.SESSION, 50)
                    .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
            }
            assertEquals(-1, prefs.getInt("session", -1))
        } finally { networkLock.unlock() }
    }

    @Test fun staleInstallCallbackCannotClearTheNewerSession() = runBlocking {
        stage()
        prefs.edit().putInt("session", 22).commit()
        KoneUpdates.installResult(app, Intent(KonePackageInstaller.ACTION).putExtra(KonePackageInstaller.SESSION, 21)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        assertEquals(22, prefs.getInt("session", -1))
        assertNotNull(prefs.getString("release", null))
    }
}
