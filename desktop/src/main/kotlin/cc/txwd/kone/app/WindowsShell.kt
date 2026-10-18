package cc.txwd.kone.app

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * What KOne asks of Windows itself: whether the person uses dark mode, and whether KOne starts
 * when they sign in. The installer registers kone:// links.
 */
object WindowsShell {
    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_NAME = "KOne"

    /** Whether Windows is set to dark for apps (Settings → Personalization → Colors). */
    fun appsDark(): Boolean = personalize("AppsUseLightTheme") == 0

    /** Whether the taskbar is dark, which the tray icon is drawn for. */
    fun taskbarDark(): Boolean = personalize("SystemUsesLightTheme") != 1

    /**
     * Whether KOne starts, by the clock, when this person signs in: the Run entry of their own
     * account (Task Manager → Startup apps shows it too). Off until they turn it on.
     */
    fun startsWithWindows(launcher: File): Boolean = Places.windows && runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, RUN_NAME) &&
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN, RUN_NAME) == runLine(launcher)
    }.getOrDefault(false)

    fun startWithWindows(launcher: File, on: Boolean) {
        if (!Places.windows) return
        // A fresh account may not have its Run key yet.
        val keyThere = Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, RUN)
        if (on) {
            if (!keyThere) Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, RUN)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, RUN_NAME, runLine(launcher))
        } else if (keyThere && Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, RUN_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN, RUN_NAME)
        }
    }

    /** KOne.exe with --tray: started by Windows it waits by the clock rather than opening its window. */
    private fun runLine(launcher: File) = "\"${launcher.path}\" --tray"

    private fun personalize(name: String): Int? = if (!Places.windows) null else runCatching {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, name)
    }.getOrNull()
}
