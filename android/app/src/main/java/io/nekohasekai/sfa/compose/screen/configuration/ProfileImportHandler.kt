package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cc.txwd.kone.shared.ClashProxies
import cc.txwd.kone.shared.ShareLinks
import cc.txwd.kone.shared.SubscriptionContent
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.selfuse.clashNodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date

/** An import refused in fixed words, safe to show: it never repeats what was being imported. */
class ImportRefusal(message: String) : Exception(message)

/** Text that lists nodes (share links, a base64 block of them, or a Clash profile) instead of being a profile. */
internal object NodeText {
    /** How many nodes [text] lists, counting those sing-box may refuse; 0 when it is not a list of nodes. */
    fun count(text: String): Int {
        val trimmed = text.trimStart().removePrefix("\uFEFF")
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return 0
        if (ClashProxies.looksLikeClash(trimmed)) return clashNodes(trimmed).let { it.nodes.size + it.refused }
        return ShareLinks.findAll(trimmed).size
    }
}

class ProfileImportHandler(private val context: Context) {
    sealed class ImportResult {
        data class Success(val profile: Profile) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    sealed class QRCodeParseResult {
        data class RemoteProfile(val name: String, val host: String, val url: String) : QRCodeParseResult()

        data class LocalProfile(val name: String) : QRCodeParseResult()

        data class Error(val message: String) : QRCodeParseResult()
    }

    sealed class QRSParseResult {
        data class Success(val name: String) : QRSParseResult()

        data class Error(val message: String) : QRSParseResult()
    }

    sealed class UriParseResult {
        data class Success(val name: String) : UriParseResult()

        data class Error(val message: String) : UriParseResult()
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val data = readFile(uri)
            val text = String(data).removePrefix("\uFEFF")
            if (isJsonConfiguration(text)) {
                // A sing-box configuration is imported as it is, as a local profile.
                return@withContext importJsonConfiguration(text, getFileNameFromUri(uri))
            }
            refuseNodeList(text)
            importProfile(decode(data))
        } catch (refusal: ImportRefusal) {
            ImportResult.Error(refusal.message.orEmpty())
        } catch (_: Exception) {
            ImportResult.Error(context.getString(R.string.kone_import_file_unopened))
        }
    }

    suspend fun parseUri(uri: Uri): UriParseResult = withContext(Dispatchers.IO) {
        try {
            val data = readFile(uri)
            val text = String(data).removePrefix("\uFEFF")
            if (isJsonConfiguration(text)) {
                return@withContext UriParseResult.Success(name = getFileNameFromUri(uri))
            }
            refuseNodeList(text)
            UriParseResult.Success(name = decode(data).name)
        } catch (refusal: ImportRefusal) {
            UriParseResult.Error(refusal.message.orEmpty())
        } catch (_: Exception) {
            UriParseResult.Error(context.getString(R.string.kone_import_file_unopened))
        }
    }

    suspend fun parseQRCode(data: String): QRCodeParseResult = withContext(Dispatchers.IO) {
        try {
            // Check if it's a sing-box remote profile import link
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext QRCodeParseResult.RemoteProfile(
                        name = profileInfo.name,
                        host = profileInfo.host,
                        url = profileInfo.url,
                    )
                } catch (e: Exception) {
                    return@withContext QRCodeParseResult.Error(context.getString(R.string.kone_import_remote_link_unreadable))
                }
            }

            // Check if it's a direct URL
            if (data.startsWith("http://") || data.startsWith("https://")) {
                // Named by its #fragment or host, never its path: a subscription's path often holds its token.
                return@withContext QRCodeParseResult.RemoteProfile(
                    name = SubscriptionContent.name(data, null),
                    host = extractHostFromUrl(data),
                    url = data,
                )
            }

            QRCodeParseResult.LocalProfile(name = decode(data.toByteArray()).name)
        } catch (refusal: ImportRefusal) {
            QRCodeParseResult.Error(refusal.message.orEmpty())
        } catch (_: Exception) {
            QRCodeParseResult.Error(context.getString(R.string.kone_import_file_unreadable))
        }
    }

    /**
     * Imports a profile carried whole in a QR code. A remote profile's link is not imported here:
     * [parseQRCode] sends it to New profile, which fetches it before keeping it.
     */
    suspend fun importFromQRCode(data: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            importProfile(decode(data.toByteArray()))
        } catch (refusal: ImportRefusal) {
            ImportResult.Error(refusal.message.orEmpty())
        } catch (_: Exception) {
            ImportResult.Error(context.getString(R.string.kone_import_file_unreadable))
        }
    }

    suspend fun parseQRSData(data: ByteArray): QRSParseResult = withContext(Dispatchers.IO) {
        try {
            QRSParseResult.Success(name = decode(data).name)
        } catch (refusal: ImportRefusal) {
            QRSParseResult.Error(refusal.message.orEmpty())
        } catch (_: Exception) {
            QRSParseResult.Error(context.getString(R.string.kone_import_file_unreadable))
        }
    }

    suspend fun importFromQRSData(data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            importProfile(decode(data))
        } catch (refusal: ImportRefusal) {
            ImportResult.Error(refusal.message.orEmpty())
        } catch (_: Exception) {
            ImportResult.Error(context.getString(R.string.kone_import_file_unreadable))
        }
    }

    /**
     * [uri]'s bytes, up to [MOST_BYTES]. Call it off the main thread. Throws [ImportRefusal] for an
     * empty or oversized file, and what the content resolver throws when it cannot open it.
     */
    fun readFile(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportRefusal(context.getString(R.string.error_empty_file))
        val out = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MOST_BYTES) throw ImportRefusal(context.getString(R.string.kone_import_file_too_large))
            }
        }
        if (out.size() == 0) throw ImportRefusal(context.getString(R.string.error_empty_file))
        return out.toByteArray()
    }

    /** Nodes are imported one profile each from the dashboard, where duplicates are recognised; not as a profile here. */
    fun refuseNodeList(text: String) {
        val nodes = NodeText.count(text)
        if (nodes > 0) throw ImportRefusal(context.resources.getQuantityString(R.plurals.kone_import_file_nodes, nodes, nodes))
    }

    private fun decode(data: ByteArray): ProfileContent = try {
        Libbox.decodeProfileContent(data)
    } catch (_: Exception) {
        // The decoder's own words describe bytes, not what to do.
        throw ImportRefusal(context.getString(R.string.kone_import_file_unreadable))
    }

    private suspend fun importProfile(content: ProfileContent): ImportResult {
        val typedProfile = TypedProfile()
        when (content.type) {
            Libbox.ProfileTypeLocal -> {
                typedProfile.type = TypedProfile.Type.Local
            }
            Libbox.ProfileTypeiCloud -> {
                return ImportResult.Error(context.getString(R.string.icloud_profile_unsupported))
            }
            Libbox.ProfileTypeRemote -> {
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = content.remotePath
                typedProfile.autoUpdate = content.autoUpdate
                typedProfile.autoUpdateInterval = content.autoUpdateInterval
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }
        return ImportResult.Success(store(content.name, typedProfile, content.config))
    }

    /** Saves [config] as a new profile's file and creates the profile, selected. */
    private suspend fun store(name: String, typedProfile: TypedProfile, config: String): Profile {
        val profile = Profile(name = name, typed = typedProfile).apply {
            userOrder = ProfileManager.nextOrder()
        }
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText(config)
        typedProfile.path = configFile.path
        try {
            ProfileManager.create(profile, andSelect = true)
        } catch (failure: Exception) {
            configFile.delete()
            throw failure
        }
        return profile
    }

    private fun extractHostFromUrl(url: String): String = try {
        val uri = Uri.parse(url)
        uri.host ?: url
    } catch (e: Exception) {
        url
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var filename = "Imported Profile"

        // Try to get filename from content resolver
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    filename = cursor.getString(nameIndex)
                        ?.substringBeforeLast(".") // Remove extension
                        ?.takeIf { it.isNotEmpty() }
                        ?: filename
                }
            }
        }

        // Fallback to getting from URI path
        if (filename == "Imported Profile") {
            uri.lastPathSegment?.let { segment ->
                filename = segment
                    .substringBeforeLast(".")
                    .takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        return filename
    }

    private fun isJsonConfiguration(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private suspend fun importJsonConfiguration(jsonContent: String, profileName: String): ImportResult {
        try {
            Libbox.checkConfig(jsonContent)
        } catch (_: Exception) {
            // sing-box's own reason may quote a node's password, so it is not shown.
            return ImportResult.Error(context.getString(R.string.kone_import_config_invalid))
        }
        val typedProfile = TypedProfile().apply { type = TypedProfile.Type.Local }
        return ImportResult.Success(store(profileName.ifEmpty { "Imported Profile" }, typedProfile, jsonContent))
    }

    private companion object {
        /** The most a file may hold: far beyond any profile, short of filling memory. */
        const val MOST_BYTES = 16 shl 20
    }
}
