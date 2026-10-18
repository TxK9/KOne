package io.nekohasekai.sfa.selfuse

import android.os.Build
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the Home settings live and how they reach the configurations: new nodes are built with
 * them, saved configurations are rewritten when they change, and the service applies them again
 * on every start so a configuration saved by an older version gets them too.
 */
object HomeSettings {
    private val enabledState by lazy { mutableStateOf(Settings.knineHomeEnabled) }

    /** Whether Home is on, as Compose state so the status line follows the switch. */
    val isEnabled: Boolean get() = enabledState.value

    private val revisionState = mutableIntStateOf(0)

    /** Rises with every save, so a settings page showing the values reads them again. */
    val revision: Int get() = revisionState.intValue

    fun current(): HomeLink.Home = HomeLink.Home(Settings.knineHomeEnabled, subnets(), hostname())

    fun subnets(): List<String> = runCatching { HomeLink.parseSubnets(Settings.knineHomeSubnets) }.getOrDefault(emptyList())

    fun hostname(): String = HomeLink.cleanHostname(Settings.knineHomeHostname).ifBlank { defaultHostname() }

    /** The name a phone gets when none is set: "kone-" and its model. */
    fun defaultHostname(): String = HomeLink.cleanHostname("kone-" + Build.MODEL).ifBlank { "kone" }

    /** The configuration the service runs: Home applied, and checked when it is on. */
    fun prepare(content: String): String {
        val prepared = HomeLink.apply(content, current())
        if (HomeLink.hasHome(prepared)) PairedProfile.requirePaired(prepared)
        return prepared
    }

    suspend fun save(enabled: Boolean, subnets: List<String>, hostname: String) = withContext(Dispatchers.IO) {
        Settings.knineHomeEnabled = enabled
        Settings.knineHomeSubnets = subnets.joinToString(",")
        Settings.knineHomeHostname = HomeLink.cleanHostname(hostname)
        withContext(Dispatchers.Main) {
            enabledState.value = enabled
            revisionState.intValue += 1
        }
        refreshSaved()
    }

    /**
     * Saved configurations built here, rewritten so what Profile content shows is what runs; also run at
     * start, so configurations saved by an older version catch up.
     */
    suspend fun refreshSaved() = withContext(Dispatchers.IO) {
        val home = current()
        ProfileManager.list().forEach { profile ->
            val file = File(profile.typed.path)
            if (!file.isFile) return@forEach
            runCatching {
                val content = file.readText()
                val updated = HomeLink.apply(content, home)
                if (updated != content) {
                    // Whole or not at all: the service may be reading this file as it starts.
                    val next = File(file.parentFile, file.name + ".next")
                    next.writeText(updated)
                    next.setReadable(false, false)
                    next.setReadable(true, true)
                    if (!next.renameTo(file)) next.delete()
                }
            }
        }
    }
}
