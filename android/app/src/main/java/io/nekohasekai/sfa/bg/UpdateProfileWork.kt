package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.selfuse.SubscriptionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                Log.e(TAG, "reconfigureUpdater", it)
            }
        }

        private suspend fun reconfigureUpdater0() {
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) {
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }

            val minDelay = remoteProfiles.minOf { it.typed.autoUpdateInterval }.toLong().coerceAtLeast(15)
            val nowSeconds = System.currentTimeMillis() / 1000L
            val minInitDelay =
                remoteProfiles.minOf { (it.typed.autoUpdateInterval * 60) - (nowSeconds - (it.typed.lastUpdated.time / 1000L)) }
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                        setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                    }
                    .build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            var selectedProfileUpdated = false
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) return@withContext Result.success()
            var retry = false
            val selectedProfile = Settings.selectedProfile
            for (profile in remoteProfiles) {
                val lastSeconds =
                    (System.currentTimeMillis() - profile.typed.lastUpdated.time) / 1000L
                if (lastSeconds < profile.typed.autoUpdateInterval * 60) {
                    continue
                }
                try {
                    // Links, base64 or a Clash profile become KOne's own template (a whole sing-box configuration
                    // is kept); the provider's terms come with it.
                    val fetched = io.nekohasekai.sfa.selfuse.SubscriptionProfile.fetch(profile.typed.remoteURL, io.nekohasekai.sfa.selfuse.SubscriptionProfile.leaveOut(profile.id))
                    val content = io.nekohasekai.sfa.selfuse.HomeSettings.saved(fetched.content)
                    Libbox.checkConfig(content)
                    io.nekohasekai.sfa.selfuse.SubscriptionProfile.keep(profile.id, fetched.info, profile.name)
                    val file = File(profile.typed.path)
                    if (!cc.txwd.kone.shared.ConfigurationContent.same(file.readText(), content)) {
                        file.writeText(content)
                        if (profile.id == selectedProfile) {
                            selectedProfileUpdated = true
                        }
                    }
                    profile.typed.lastUpdated = Date()
                    ProfileManager.update(profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A refusal's words never carry the link, so they can be logged as they are.
                    if (e is SubscriptionException) Log.w(TAG, "update profile ${profile.name}: ${e.message}") else Log.e(TAG, "update profile ${profile.name}", e)
                    retry = retry || worthRetrying(e)
                }
            }
            if (selectedProfileUpdated) {
                runCatching {
                    Libbox.newStandaloneCommandClient().serviceReload()
                }
            }
            // A link the provider refused or a body KOne cannot use waits for the next period
            // instead of being fetched again every few minutes.
            if (retry) Result.retry() else Result.success()
        }
    }
}

/** Whether a failed refresh may succeed soon: a network hiccup or a busy provider, not a refusal. */
internal fun worthRetrying(failure: Exception): Boolean = when (failure) {
    is SubscriptionException -> failure.temporary
    is IOException -> true
    else -> false
}
