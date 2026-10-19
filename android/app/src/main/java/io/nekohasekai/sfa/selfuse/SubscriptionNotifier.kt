package io.nekohasekai.sfa.selfuse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.ServiceNotification
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.database.Settings

/**
 * A subscription's warning said once as a notification, as KOne for Windows says it: ended,
 * ending within three days, or nine tenths of its data used. The same warning for the same
 * profile is not said again; a new one (a day nearer the end, say) is.
 */
object SubscriptionNotifier {
    private const val CHANNEL_ID = "kone_subscriptions"
    private const val NOTIFICATION_ID_BASE = 0x6B10

    fun warn(context: Context, profileId: Long, warning: String) {
        val said = Settings.knineSubscriptionWarned
        if (SubscriptionTerms.said(said, profileId) == warning) return
        Settings.knineSubscriptionWarned = SubscriptionTerms.withSaid(said, profileId, warning)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Subscriptions", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or ServiceNotification.flags,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle(warning)
            .setContentText("Renew it with your provider, or add another")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + (profileId % 64).toInt(), notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: the warning still shows on the profile.
        }
    }
}
