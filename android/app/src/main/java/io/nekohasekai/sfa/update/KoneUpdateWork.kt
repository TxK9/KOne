package io.nekohasekai.sfa.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object KoneUpdateWork {
    fun schedule(context: Context) {
        if (!KoneUpdates.enabled) return
        val request = PeriodicWorkRequestBuilder<Task>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("KOneAppUpdate", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    class Task(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = if (KoneUpdates.check(applicationContext)) Result.success() else Result.retry()
    }
}
