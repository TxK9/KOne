package io.nekohasekai.sfa.bg

import android.app.KeyguardManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

@RequiresApi(24)
class TileService :
    TileService(),
    ServiceConnection.Callback {
    private val connection = ServiceConnection(this, this)
    private val onHealth: () -> Unit = {
        qsTile?.apply {
            applyHealth(this)
            updateTile()
        }
    }

    private fun applyBranding(tile: Tile) {
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_kone_mountain)
    }

    /** While connected, the node's health under the name, as in the notification. */
    private fun applyHealth(tile: Tile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val reading = NodeWatch.reading
        tile.subtitle = if (connection.status == Status.Started && reading != null) io.nekohasekai.sfa.compose.screen.dashboard.NodeHealthRules.badge(reading) else null
    }

    override fun onServiceStatusChanged(status: Status) {
        qsTile?.apply {
            applyBranding(this)
            state =
                when (status) {
                    Status.Started -> Tile.STATE_ACTIVE
                    Status.Stopped -> Tile.STATE_INACTIVE
                    else -> Tile.STATE_UNAVAILABLE
                }
            applyHealth(this)
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            applyBranding(this)
            applyHealth(this)
            updateTile()
        }
        NodeWatch.addListener(onHealth)
        connection.connect()
    }

    override fun onStopListening() {
        NodeWatch.removeListener(onHealth)
        connection.disconnect()
        super.onStopListening()
    }

    override fun onClick() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            unlockAndRun {
                toggleService()
            }
        } else {
            toggleService()
        }
    }

    private fun toggleService() {
        when (connection.status) {
            Status.Stopped -> BoxService.start()
            Status.Started -> BoxService.stop()
            else -> {}
        }
    }
}
