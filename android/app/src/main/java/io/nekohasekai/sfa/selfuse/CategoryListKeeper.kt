package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.CategoryLists
import cc.txwd.kone.shared.ListDownload
import cc.txwd.kone.shared.ListStatus
import cc.txwd.kone.shared.Rules
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The category lists the person's rules name (geosite-netflix, geoip-jp), kept on the phone the
 * way KOne for Windows keeps them ([CategoryLists], from the shared core): downloaded over HTTPS
 * from SagerNet's fixed address, checked by the App's own sing-box before it is kept, looked at
 * again once a day. The configurations read them as local lists, so a rule waits for its list
 * rather than a download holding up the start; a list that arrives later rewrites the saved
 * configurations and reloads a running service once.
 */
object CategoryListKeeper {
    private val folder by lazy { File(Application.application.filesDir, "category-lists") }
    private val lists by lazy { CategoryLists(folder, ::download, ::parses) }
    private val updating = Mutex()

    /** The lists the person's rules name, each once. */
    fun named(): List<String> = Rules.sets(OwnRules.clean(RoutingSettings.current().rules))

    /** The lists kept intact, each name to its file, for [Routing.apply]. */
    fun ready(): Map<String, String> = named().filter { lists.digest(it) != null }.associateWith { File(folder, "$it.srs").absolutePath }

    /** How each named list stands, for Settings → Routing. */
    fun status(): Map<String, ListStatus> = named().associateWith(lists::status)

    /**
     * Downloads each named list that is due (every one, when [force]) and lets go of the rest.
     * When a list is kept for the first time, the saved configurations are rewritten and a running
     * service reloads, so its rules take effect; a newer copy of a list in use is read by sing-box
     * itself as the file changes.
     */
    suspend fun update(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!updating.tryLock()) return@withContext
        try {
            val names = named()
            lists.keepOnly(names.toSet())
            val before = ready().keys
            val now = System.currentTimeMillis()
            names.filter { force || lists.due(it, now) }.forEach { lists.update(it, now) }
            if (!before.containsAll(ready().keys)) {
                HomeSettings.refreshSaved()
                runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
            }
        } finally {
            updating.unlock()
        }
    }

    /** HTTPS only, at most [CategoryLists.MOST_BYTES]; asking only whether it changed when [etag] is known. */
    private fun download(url: String, etag: String?): ListDownload {
        require(url.startsWith("https://")) { "HTTPS only" }
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "KOne")
                if (etag != null) setRequestProperty("If-None-Match", etag)
            }
        } catch (_: IOException) {
            throw IOException("Could not reach the list's server")
        }
        try {
            val code = try { connection.responseCode } catch (_: IOException) { throw IOException("Could not reach the list's server") }
            if (code == 304) return ListDownload.Same
            if (code == 404) throw IOException("No such list")
            // A redirect is followed only to HTTPS: Android does not follow one to another scheme.
            if (code != 200 || connection.url.protocol != "https") throw IOException("The server answered $code")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { body ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > CategoryLists.MOST_BYTES) throw IOException("The list is too large")
                }
            }
            return ListDownload.Got(out.toByteArray(), connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    /** Whether the App's sing-box reads [file] as the configurations will use it. */
    private fun parses(file: File): Boolean = runCatching {
        val set = JSONObject().put("type", "local").put("tag", "check").put("format", "binary").put("path", file.absolutePath)
        Libbox.checkConfig(JSONObject().put("route", JSONObject().put("rule_set", JSONArray().put(set))).toString())
    }.isSuccess
}
