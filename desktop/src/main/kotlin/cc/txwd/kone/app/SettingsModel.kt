package cc.txwd.kone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.SettingsState
import cc.txwd.kone.ui.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Settings and what it does: the appearance kept in the App's folder, starting with Windows (off until
 * the person turns it on), and updates from the private edition website, looked for once a day and
 * installed only when the person says Update.
 */
class SettingsModel(private val scope: CoroutineScope, private val preferences: Preferences, private val onQuit: () -> Unit) {
    private val updater: Updater? = Updater.configured()
    private var release: Release? = null

    var state by mutableStateOf(
        SettingsState(
            appearance = preferences.appearance,
            autostart = Places.launcher?.let(WindowsShell::startsWithWindows),
            version = Places.version,
            update = if (updater == null) UpdateState.Off else UpdateState.Idle,
        ),
    )
        private set

    init {
        if (updater != null) {
            scope.launch {
                delay(TimeUnit.MINUTES.toMillis(1))
                while (isActive) {
                    if (state.update == UpdateState.Idle || state.update == UpdateState.Latest || state.update is UpdateState.Failed) check(quiet = true)
                    delay(TimeUnit.DAYS.toMillis(1))
                }
            }
        }
    }

    fun pickAppearance(appearance: Appearance) {
        preferences.appearance = appearance
        state = state.copy(appearance = appearance)
    }

    fun setAutostart(on: Boolean) {
        val launcher = Places.launcher ?: return
        runCatching { WindowsShell.startWithWindows(launcher, on) }
        state = state.copy(autostart = WindowsShell.startsWithWindows(launcher))
    }

    fun check() = check(quiet = false)

    /** Quietly, a failure leaves things as they were: the person did not ask. */
    private fun check(quiet: Boolean) {
        val updater = updater ?: return
        if (!quiet) state = state.copy(update = UpdateState.Checking)
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { updater.check() } }
            found.onSuccess { newer ->
                release = newer
                state = state.copy(update = if (newer != null) UpdateState.Available(newer.version) else UpdateState.Latest)
            }.onFailure { failure ->
                if (!quiet) state = state.copy(update = UpdateState.Failed((failure as? UpdateException)?.message ?: "Check failed"))
            }
        }
    }

    /** Downloads the newer KOne, checks it against the signed manifest, hands it to Windows Installer and quits. */
    fun update() {
        val updater = updater ?: return
        val newer = release ?: return
        val launcher = Places.launcher ?: return
        state = state.copy(update = UpdateState.Downloading(0))
        scope.launch {
            val msi = withContext(Dispatchers.IO) {
                runCatching {
                    var shown = 0
                    updater.download(newer) { part ->
                        val percent = (part * 100).toInt().coerceIn(0, 100)
                        if (percent != shown) {
                            shown = percent
                            scope.launch { state = state.copy(update = UpdateState.Downloading(percent)) }
                        }
                    }
                }
            }
            msi.onSuccess { file ->
                state = state.copy(update = UpdateState.Installing)
                val started = withContext(Dispatchers.IO) { runCatching { Updater.install(file, launcher) }.isSuccess }
                if (started) onQuit() else state = state.copy(update = UpdateState.Failed("Cannot install"))
            }.onFailure { failure ->
                state = state.copy(update = UpdateState.Failed((failure as? UpdateException)?.message ?: "Download failed"))
            }
        }
    }
}
