package io.nekohasekai.sfa.selfuse

import android.content.Context
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import java.io.File

/** Debug-only import from this App's private directory, never shared Downloads or clipboard. */
object PrivateBootstrap {
    suspend fun consume(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val link = take(File(context.filesDir, "inbox/vless.txt")) ?: return false
        PairedProfileStore.save(context, link, select = true)
        Settings.rebuildServiceMode()
        return true
    }

    /**
     * Reads the pending link and removes it before anything is imported. The link is a credential,
     * so it never stays on disk after a start, and a file that cannot be removed is not imported,
     * because it would otherwise create another copy of the profile on every start.
     */
    internal fun take(input: File): String? {
        if (!input.isFile) return null
        try {
            require(input.length() in 1..MAX_LINK_BYTES) { "Invalid import file" }
            return input.readText()
        } finally {
            check(input.delete() || !input.exists()) { "Cannot remove the import file" }
        }
    }

    private const val MAX_LINK_BYTES = 16384L
}
