package io.nekohasekai.sfa.selfuse

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import java.io.File

/** Debug-only import from this App's private directory, never shared Downloads or clipboard. */
object PrivateBootstrap {
    suspend fun consume(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val input = File(context.filesDir, "inbox/vless.txt")
        if (!input.isFile()) return false
        require(input.length() in 1..16384) { context.getString(R.string.knine_error_bad_import) }
        val link = input.readText()
        val config = PairedProfile.fromVlessLink(link, InitialRuleSets.ensure(context))
        Libbox.checkConfig(config)
        val fileId = ProfileManager.nextFileID()
        val directory = File(context.filesDir, "configs").also { it.mkdirs() }
        val file = File(directory, "$fileId.json")
        try {
            file.writeText(config)
            file.setReadable(false, false)
            file.setReadable(true, true)
            val typed = TypedProfile().apply { type = TypedProfile.Type.Local; path = file.absolutePath }
            val profile = Profile(name = PairedProfile.displayName(link) ?: context.getString(R.string.knine_import_profile_name), typed = typed).apply { userOrder = ProfileManager.nextOrder() }
            ProfileManager.create(profile, andSelect = true)
            Settings.rebuildServiceMode()
            input.delete()
            return true
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
