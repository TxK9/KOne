package io.nekohasekai.sfa.selfuse

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** The core's own check failed: the link parsed, so "check the link format" would mislead. */
class PairedProfileCoreException(message: String?) : Exception(message) {
    // Core errors are English diagnostics; keep one short line under the localized prefix.
    val detail: String? = message?.lineSequence()?.firstOrNull()?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
}

/** Saves a VLESS link as a paired Proxy and Home profile; shared by the import dialog and the debug bootstrap. */
object PairedProfileStore {
    private val lock = Mutex()

    /**
     * Serialized: the dialog and the debug bootstrap can run at the same time, and both would
     * otherwise take the same next file ID and write the same configuration file.
     */
    suspend fun save(context: Context, link: String, select: Boolean): Profile = lock.withLock {
        val content = PairedProfile.fromVlessLink(link, InitialRuleSets.ensure(context))
        try {
            Libbox.checkConfig(content)
        } catch (failure: Exception) {
            throw PairedProfileCoreException(failure.message)
        }
        val file = writeConfig(File(context.filesDir, "configs"), ProfileManager.nextFileID(), content)
        try {
            val typed = TypedProfile().apply { type = TypedProfile.Type.Local; path = file.absolutePath }
            val profile = Profile(name = PairedProfile.displayName(link), typed = typed).apply { userOrder = ProfileManager.nextOrder() }
            ProfileManager.create(profile, andSelect = select)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    /**
     * Writes [content] to the first unused `<id>.json` at or after [firstId]. A file left behind by
     * an interrupted import or another importer is never overwritten, and the configuration only
     * appears under its final name once it is complete.
     */
    internal fun writeConfig(directory: File, firstId: Long, content: String): File {
        directory.mkdirs()
        val target = generateSequence(firstId) { it + 1 }.map { File(directory, "$it.json") }.first { !it.exists() }
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.writeText(content)
            temporary.setReadable(false, false)
            temporary.setReadable(true, true)
            check(temporary.renameTo(target)) { "Cannot save the profile configuration" }
        } finally {
            temporary.delete()
        }
        return target
    }
}
