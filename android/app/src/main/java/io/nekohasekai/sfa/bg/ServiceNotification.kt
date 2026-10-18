package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.compose.screen.dashboard.NodeHealthRules
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false

    // The node in use, its health and its traffic: the notification's two lines.
    private var title = ""
    private var traffic: String? = null
    private var dynamic = false

    @Volatile
    private var running = false
    private val onHealth: () -> Unit = { refresh() }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle(service.getString(R.string.app_name)).setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    fun show(lastProfileName: String, @StringRes contentTextId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Connection status",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        title = lastProfileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_name)
        service.startForeground(
            notificationId,
            notificationBuilder
                .setContentTitle(title)
                .setContentText(service.getString(contentTextId)).build(),
        )
    }

    /** Main thread. Another node is in use; its name goes in the title, and its health starts over. */
    fun retitle(lastProfileName: String) {
        title = lastProfileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_name)
        refresh()
    }

    suspend fun start() {
        val traffic = Settings.dynamicNotification && checkPermission()
        withContext(Dispatchers.Main) {
            running = true
            dynamic = traffic
            NodeWatch.addListener(onHealth)
            // The screen decides both the traffic and the health checks: nobody sees either with it off.
            registerReceiver()
            if (Application.powerManager.isInteractive) NodeWatch.resume()
        }
        if (traffic) {
            commandClient.connect()
        }
    }

    /**
     * The node's weather first (Weather seal: sun, mist, storm) and its latency, then the traffic when that is
     * on, with the weather's seal as the notification's picture and its pigment as the accent.
     * Before the first test, and with traffic off, it just says the connection is up.
     */
    private fun refresh() {
        if (!running) return
        val reading = NodeWatch.reading
        val weather = reading?.let { NodeWeather.of(it.health) }
        val content = listOfNotNull(reading?.let { NodeHealthRules.badge(it) }, traffic).joinToString("   ").ifEmpty { service.getString(R.string.status_started) }
        notificationBuilder
            .setContentTitle(title)
            .setContentText(content)
            .setColor(weather?.pigment?.toInt() ?: NotificationCompat.COLOR_DEFAULT)
            .setLargeIcon(weather?.let { WeatherSealPicture.of(service, it) })
        Application.notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    override fun updateStatus(status: StatusMessage) {
        val content = "↑ " + Libbox.formatBytes(status.uplink) + "/s  ↓ " + Libbox.formatBytes(status.downlink) + "/s"
        main.post {
            traffic = content
            refresh()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                if (dynamic) commandClient.connect()
                NodeWatch.resume()
            }

            Intent.ACTION_SCREEN_OFF -> {
                commandClient.disconnect()
                NodeWatch.pause()
            }
        }
    }

    fun close() {
        running = false
        main.post {
            traffic = null
            NodeWatch.removeListener(onHealth)
            NodeWatch.stop()
        }
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
