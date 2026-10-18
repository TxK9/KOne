package cc.txwd.kone.app

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Ctrl+Alt+K, anywhere in Windows, turns the connection on or off (Settings → Keyboard shortcut, off until the
 * person turns it on). Windows gives a key to one program only; when another has it, KOne goes without.
 */
class Hotkey(private val onPress: () -> Unit) {
    private var loop: Thread? = null
    private var threadId = 0

    /** Takes the key for KOne, or lets it go; false when it was wanted and Windows would not give it. */
    @Synchronized
    fun take(on: Boolean): Boolean {
        letGo()
        if (!on) return true
        if (!Places.windows) return false
        val taken = CompletableFuture<Boolean>()
        // The key's presses come to the thread that took it, as messages; it waits for them.
        val thread = Thread({
            val user32 = User32.INSTANCE
            if (!user32.RegisterHotKey(null, ID, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, VK_K)) {
                taken.complete(false)
                return@Thread
            }
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            taken.complete(true)
            val message = WinUser.MSG()
            while (user32.GetMessage(message, null, 0, 0) > 0) {
                if (message.message == WM_HOTKEY && message.wParam.toInt() == ID) onPress()
            }
            user32.UnregisterHotKey(null, ID)
        }, "KOne hotkey").apply { isDaemon = true }
        thread.start()
        val held = runCatching { taken.get(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (held) loop = thread
        return held
    }

    private fun letGo() {
        val thread = loop ?: return
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        thread.join(1_000)
        loop = null
    }

    companion object {
        /** As Settings shows it. */
        const val KEYS = "Ctrl+Alt+K"

        private const val ID = 1
        private const val MOD_ALT = 0x0001
        private const val MOD_CONTROL = 0x0002
        private const val MOD_NOREPEAT = 0x4000
        private const val VK_K = 0x4B
        private const val WM_HOTKEY = 0x0312
    }
}
