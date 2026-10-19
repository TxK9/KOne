package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.ShareLinks
import android.content.Context
import android.net.Uri
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.screen.dashboard.NodeCategory
import io.nekohasekai.sfa.compose.screen.dashboard.NodeCategoryStore
import io.nekohasekai.sfa.compose.theme.KnineAppearance
import io.nekohasekai.sfa.compose.theme.KnineSwitcherStyle
import io.nekohasekai.sfa.compose.theme.WallpaperCatalog
import io.nekohasekai.sfa.compose.theme.WallpaperMode
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup: the nodes, their categories and the settings that shape KOne, in one file the user keeps
 * wherever they like. The system backup is off for this App, so this is what carries a setup to a
 * new phone. The Tailscale login is not in it; Home signs in again on the new phone.
 *
 * Restoring adds what is missing and leaves what is there: a node already on the phone is not
 * added twice, and an existing category keeps its name and place.
 */
object KoneBackup {
    private const val FORMAT = 1

    class Restored(val added: Int, val existing: Int)

    class BackupException(message: String) : Exception(message)

    suspend fun write(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val nodes = JSONArray()
        // A node a KOne console manages belongs to this phone's binding, not to a copy elsewhere.
        ProfileManager.list().filterNot { io.nekohasekai.sfa.managed.ManagedState.isManaged(it.id) }.forEach { profile ->
            val content = runCatching { File(profile.typed.path).readText() }.getOrNull() ?: return@forEach
            nodes.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("icon", profile.icon.orEmpty())
                    .put("remote", profile.typed.type == TypedProfile.Type.Remote)
                    .put("remote_url", profile.typed.remoteURL)
                    .put("auto_update", profile.typed.autoUpdate)
                    .put("auto_update_interval", profile.typed.autoUpdateInterval)
                    .put("content", content),
            )
        }
        val categories = NodeCategoryStore.state
        val backup = JSONObject()
            .put("kone_backup", FORMAT)
            .put("app", "KOne ${BuildConfig.VERSION_NAME}")
            .put("nodes", nodes)
            .put("selected", Settings.selectedProfile)
            .put(
                "categories",
                JSONObject()
                    .put("list", JSONArray(categories.categories.map { JSONObject().put("id", it.id).put("name", it.name).put("flag", it.flag.orEmpty()) }))
                    .put("fallback_name", categories.fallbackName)
                    .put("fallback_flag", categories.fallbackFlag.orEmpty())
                    .put("assignments", JSONObject().apply { categories.assignments.forEach { (id, category) -> put(id.toString(), category) } }),
            )
            .put(
                "settings",
                JSONObject()
                    .put("wallpaper", WallpaperCatalog.mode.id)
                    .put("switcher", KnineAppearance.switcher.id)
                    .put("home_enabled", Settings.knineHomeEnabled)
                    .put("home_subnets", Settings.knineHomeSubnets)
                    .put("home_hostname", Settings.knineHomeHostname)
                    .put("routing", RoutingSettings.current().let { routing ->
                        JSONObject()
                            .put("direct", JSONArray(routing.direct)).put("proxied", JSONArray(routing.proxied))
                            .put("rules", JSONArray(routing.rules.map(OwnRules::toJson)))
                            .put("block_ads", routing.blockAds).put("unmatched_direct", routing.unmatchedDirect)
                            .put("remote_dns", routing.remoteDns).put("direct_dns", routing.directDns).put("ipv6", routing.ipv6).put("region", routing.region)
                            .put("hosts", RoutingSettings.hostLines(routing.hosts)).put("name_servers", RoutingSettings.nameServerLines(routing.nameServers))
                    }),
            )
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Cannot write to this location")
        stream.use { it.write(backup.toString(2).toByteArray()) }
        nodes.length()
    }

    suspend fun read(context: Context, uri: Uri): Restored = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw BackupException("Cannot read this file")
        val backup = runCatching { JSONObject(text) }.getOrNull()
        if (backup == null || backup.optInt("kone_backup") != FORMAT) throw BackupException("This is not a KOne backup file.")

        val existing = ProfileManager.list()
        val existingKeys = existing.mapNotNull { profile ->
            runCatching { File(profile.typed.path).readText() }.getOrNull()?.let { keyOf(it) }?.let { it to profile.id }
        }.toMap()
        // With nothing selected yet, the node selected in the backup is selected as it comes in.
        val wanted = backup.optLong("selected", -1L).takeIf { Settings.selectedProfile == -1L }
        val ids = mutableMapOf<Long, Long>()
        val newIds = mutableSetOf<Long>()
        var added = 0
        var already = 0
        val nodes = backup.optJSONArray("nodes") ?: JSONArray()
        val directory = File(context.filesDir, "configs").also { it.mkdirs() }
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            val content = node.optString("content").takeIf { it.isNotBlank() } ?: continue
            val oldId = node.optLong("id", -1L)
            val key = keyOf(content)
            val match = key?.let { existingKeys[it] }
            if (match != null) {
                ids[oldId] = match
                if (oldId == wanted) Settings.selectedProfile = match
                already++
                continue
            }
            val file = File(directory, "${ProfileManager.nextFileID()}.json")
            file.writeText(HomeLink.apply(content, HomeSettings.current()))
            file.setReadable(false, false)
            file.setReadable(true, true)
            val typed = TypedProfile().apply {
                path = file.absolutePath
                type = if (node.optBoolean("remote")) TypedProfile.Type.Remote else TypedProfile.Type.Local
                remoteURL = node.optString("remote_url")
                autoUpdate = node.optBoolean("auto_update")
                autoUpdateInterval = node.optInt("auto_update_interval", 60)
            }
            val profile = Profile(name = node.optString("name").ifBlank { "Node" }, icon = node.optString("icon").ifBlank { null }, typed = typed)
                .apply { userOrder = ProfileManager.nextOrder() }
            val created = ProfileManager.create(profile, andSelect = oldId == wanted)
            ids[oldId] = created.id
            newIds += created.id
            added++
        }

        backup.optJSONObject("categories")?.let { categories ->
            val list = categories.optJSONArray("list") ?: JSONArray()
            val restored = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { item ->
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NodeCategory(id, item.optString("name"), item.optString("flag").ifBlank { null })
            }
            val assignments = categories.optJSONObject("assignments")
            val placed = assignments?.keys()?.asSequence()?.mapNotNull { key ->
                val newId = key.toLongOrNull()?.let { ids[it] }?.takeIf { it in newIds } ?: return@mapNotNull null
                newId to assignments.optString(key)
            }?.toMap().orEmpty()
            NodeCategoryStore.restore(
                restored,
                placed,
                categories.optString("fallback_name"),
                categories.optString("fallback_flag").ifBlank { null },
            )
        }

        backup.optJSONObject("settings")?.let { settings ->
            withContext(Dispatchers.Main) {
                WallpaperMode.entries.firstOrNull { it.id == settings.optString("wallpaper") }
                    ?.takeIf { (it != WallpaperMode.Builtin || WallpaperCatalog.hasBundledPhotos) && (it != WallpaperMode.Photo || WallpaperCatalog.photoFile.isFile) }
                    ?.let { WallpaperCatalog.setMode(it) }
                settings.optString("switcher").takeIf { it.isNotBlank() }?.let { KnineAppearance.switcher = KnineSwitcherStyle.of(it) }
            }
            if (settings.has("home_enabled")) {
                HomeSettings.save(
                    settings.optBoolean("home_enabled"),
                    runCatching { HomeLink.parseSubnets(settings.optString("home_subnets")) }.getOrDefault(emptyList()),
                    settings.optString("home_hostname"),
                )
            }
            settings.optJSONObject("routing")?.let { routing ->
                fun names(key: String) = routing.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
                RoutingSettings.save(
                    Routing.Choices(
                        direct = names("direct"), proxied = names("proxied"),
                        rules = routing.optJSONArray("rules")?.let { array -> (0 until array.length()).mapNotNull { OwnRules.fromJson(array.optJSONObject(it)) } }.orEmpty(),
                        blockAds = routing.optBoolean("block_ads"), unmatchedDirect = routing.optBoolean("unmatched_direct"),
                        remoteDns = routing.optString("remote_dns"), directDns = routing.optString("direct_dns"), ipv6 = routing.optBoolean("ipv6"),
                        region = routing.optString("region").ifEmpty { RoutingSettings.current().region },
                        hosts = routing.optString("hosts").lines().mapNotNull(cc.txwd.kone.shared.Hosts::parse),
                        nameServers = routing.optString("name_servers").lines().mapNotNull(cc.txwd.kone.shared.NameServers::parse),
                    ),
                )
            }
        }

        Restored(added, already)
    }

    /** What makes two configurations the same node: the node itself for KOne's, else the whole text. */
    private fun keyOf(content: String): String? {
        val config = runCatching { JSONObject(content) }.getOrNull() ?: return content.trim().ifEmpty { null }
        val outbounds = config.optJSONArray("outbounds") ?: return config.toString()
        val node = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }.firstOrNull { it.optString("tag") == ShareLinks.NODE_TAG }
        return PairedProfile.nodeKey(node) ?: config.toString()
    }
}
