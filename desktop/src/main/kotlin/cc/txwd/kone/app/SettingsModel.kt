package cc.txwd.kone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Backdrop
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
 * Settings and what it does: the appearance kept in the App's folder, starting with Windows and the
 * hotkey (both off until the person turns them on), and updates from the private edition website, looked
 * for once a day and installed only when the person says Update. [onHotkey] runs on the UI thread.
 */
class SettingsModel(
    private val scope: CoroutineScope,
    private val preferences: Preferences,
    private val onQuit: () -> Unit,
    onHotkey: () -> Unit = {},
) {
    private val updater: Updater? = Updater.configured()
    private var release: Release? = null
    private val hotkey = Hotkey { scope.launch { onHotkey() } }

    var state by mutableStateOf(
        SettingsState(
            appearance = preferences.appearance,
            backdrop = preferences.backdrop,
            sound = preferences.sound,
            autostart = Places.launcher?.let(WindowsShell::startsWithWindows),
            hotkey = preferences.hotkey.takeIf { Places.windows },
            // Shown as on the phone (the private edition choice); the build's own 1.0.<n> still tells updates apart.
            version = "1.0.0",
            update = if (updater == null) UpdateState.Off else UpdateState.Idle,
        ),
    )
        private set

    init {
        if (preferences.hotkey) setHotkey(true)
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

    fun pickBackdrop(backdrop: Backdrop) {
        preferences.backdrop = backdrop
        state = state.copy(backdrop = backdrop)
    }

    fun setSound(on: Boolean) {
        preferences.sound = on
        state = state.copy(sound = on)
    }

    fun setAutostart(on: Boolean) {
        val launcher = Places.launcher ?: return
        runCatching { WindowsShell.startWithWindows(launcher, on) }
        state = state.copy(autostart = WindowsShell.startsWithWindows(launcher))
    }

    fun setHotkey(on: Boolean) {
        if (!Places.windows) return
        preferences.hotkey = on
        state = state.copy(hotkey = on, hotkeyTaken = false)
        scope.launch {
            val held = withContext(Dispatchers.IO) { hotkey.take(on) }
            if (preferences.hotkey == on) state = state.copy(hotkeyTaken = on && !held)
        }
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
