package cc.txwd.kone.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Properties

/**
 * Lists of the person's own, by address ("url" rules), as Clash's rule providers: a sing-box rule
 * set (binary .srs or source JSON), or a Clash list (a provider's `payload:`, or one entry a
 * line: DOMAIN-SUFFIX,example.com, +.example.com, 192.0.2.0/24). A Clash list is turned into a
 * sing-box source list once, when it is downloaded. Each is kept as category lists are
 * ([CategoryLists]): HTTPS only, checked by the App's sing-box before it replaces the last good
 * copy, looked at again once a day, and a rule waits for its list rather than holding up the start.
 */
object RuleProviders {
    const val MOST_URL = 1024

    /** [text] as a list's address: HTTPS, a host, no sign-in in it, no fragment; null otherwise. */
    fun clean(text: String): String? {
        val url = text.trim()
        if (url.length > MOST_URL || !url.startsWith("https://") || url.any { it.isWhitespace() || it.code < 0x21 }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null) return null
        val host = uri.host?.lowercase() ?: return null
        if (!Names.isSite(host) && Names.address(host.removePrefix("[").removeSuffix("]")) == null) return null
        return url
    }

    /** The rule set's tag for the list at [url]: fixed by its address, and safe as a file name. */
    fun tag(url: String): String = "list-" + CategoryLists.sha256(url.toByteArray()).take(12)

    fun isTag(tag: String) = TAG.matches(tag)

    private val TAG = Regex("""list-[0-9a-f]{12}""")

    /**
     * What a list at [url] is called on screen: its file's name without the extension
     * ("Netflix" for …/Netflix.yaml), or its host. The rest of the address is never shown.
     */
    fun title(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val stem = uri?.path?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && it.length <= 40 }
        return stem ?: uri?.host ?: url.take(40)
    }

    /** A list as kept: its bytes and the sing-box format they are in. */
    class Kept(val bytes: ByteArray, val format: String) {
        val extension get() = if (format == BINARY) "srs" else "json"
    }

    const val BINARY = "binary"
    const val SOURCE = "source"

    /** [bytes] as a list sing-box reads, or null when they are none of the kinds taken. */
    fun read(bytes: ByteArray): Kept? {
        if (bytes.isEmpty() || bytes.size > CategoryLists.MOST_BYTES) return null
        if (CategoryLists.looksLikeAList(bytes)) return Kept(bytes, BINARY)
        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val json = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
            val version = (json["version"] as? JsonPrimitive)?.intOrNull
            val rules = json["rules"] as? JsonArray
            return if (version != null && version in 1..4 && rules != null && rules.isNotEmpty()) Kept(bytes, SOURCE) else null
        }
        return clash(text)?.let { Kept(it.toString().toByteArray(), SOURCE) }
    }

    /** A Clash list (classical, domain or address entries) as a sing-box source list; null when nothing in it is taken. */
    fun clash(text: String): JsonObject? {
        val domain = LinkedHashSet<String>()
        val suffix = LinkedHashSet<String>()
        val keyword = LinkedHashSet<String>()
        val regex = LinkedHashSet<String>()
        val cidr = LinkedHashSet<String>()
        val lines = text.lines()
        val payload = lines.indexOfFirst { it.trim() == "payload:" }
        for (raw in if (payload >= 0) lines.drop(payload + 1) else lines) {
            var line = raw.substringBefore('#').trim()
            if (line.startsWith("- ")) line = line.removePrefix("- ").trim()
            line = line.trim('\'', '"').trim()
            if (line.isEmpty() || line.endsWith(":")) continue
            val parts = line.split(',').map { it.trim() }
            if (parts.size >= 2) {
                val value = parts[1].lowercase()
                when (parts[0].uppercase()) {
                    "DOMAIN" -> value.takeIf(Names::isSite)?.let(domain::add)
                    "DOMAIN-SUFFIX" -> value.removePrefix(".").takeIf(Names::isSite)?.let(suffix::add)
                    "DOMAIN-KEYWORD" -> value.takeIf { KEYWORD.matches(it) }?.let(keyword::add)
                    "IP-CIDR", "IP-CIDR6" -> Names.network(value)?.let(cidr::add)
                }
                continue
            }
            val entry = line.lowercase()
            when {
                entry.startsWith("+.") -> entry.removePrefix("+.").takeIf(Names::isSite)?.let(suffix::add)
                entry.startsWith("*.") -> entry.removePrefix("*.").takeIf(Names::isSite)?.let { regex.add("^[^.]+\\." + it.replace(".", "\\.") + "$") }
                entry.startsWith(".") -> entry.removePrefix(".").takeIf(Names::isSite)?.let(suffix::add)
                '/' in entry || ':' in entry || entry.firstOrNull()?.isDigit() == true && Names.address(entry) != null -> Names.network(entry)?.let(cidr::add)
                Names.isSite(entry) -> domain.add(entry)
            }
        }
        if (domain.isEmpty() && suffix.isEmpty() && keyword.isEmpty() && regex.isEmpty() && cidr.isEmpty()) return null
        return buildJsonObject {
            put("version", 2)
            putJsonArray("rules") {
                // Names and addresses as rules of their own: either matching is enough.
                if (domain.isNotEmpty() || suffix.isNotEmpty() || keyword.isNotEmpty() || regex.isNotEmpty()) add(
                    buildJsonObject {
                        if (domain.isNotEmpty()) putJsonArray("domain") { domain.forEach { add(it) } }
                        if (suffix.isNotEmpty()) putJsonArray("domain_suffix") { suffix.forEach { add(it) } }
                        if (keyword.isNotEmpty()) putJsonArray("domain_keyword") { keyword.forEach { add(it) } }
                        if (regex.isNotEmpty()) putJsonArray("domain_regex") { regex.forEach { add(it) } }
                    },
                )
                if (cidr.isNotEmpty()) add(buildJsonObject { putJsonArray("ip_cidr") { cidr.forEach { add(it) } } })
            }
        }
    }

    private val KEYWORD = Regex("""[a-z0-9.-]{2,63}""")
}

/**
 * The lists of the person's own ([RuleProviders]) the rules name, kept by the App in [folder]:
 * downloaded by [fetch], read, checked by [parses] (the App's sing-box, given the file and its
 * format) and only then put in place of the copy before. A failed download or a list that fails a
 * check leaves the last good copy as it was. Looked at again once a day; after a failure, an hour.
 */
class OwnLists(
    private val folder: File,
    private val fetch: (url: String, etag: String?) -> ListDownload,
    private val parses: (File, String) -> Boolean,
) {
    private val index = File(folder, "lists.properties")
    private val values = Properties().apply { runCatching { index.inputStream().use(::load) } }

    /** The file kept intact for [url], or null when none is. */
    @Synchronized
    fun file(url: String): File? {
        val tag = RuleProviders.tag(url)
        val sha = values.getProperty("$tag.sha") ?: return null
        val file = File(folder, "$tag.${values.getProperty("$tag.ext") ?: return null}")
        if (!file.isFile || file.length() > CategoryLists.MOST_BYTES * 2) return null
        return file.takeIf { CategoryLists.sha256(it.readBytes()) == sha }
    }

    @Synchronized
    fun status(url: String): ListStatus {
        val tag = RuleProviders.tag(url)
        return ListStatus(file(url) != null, values.getProperty("$tag.checked")?.toLongOrNull(), values.getProperty("$tag.error"))
    }

    @Synchronized
    fun due(url: String, now: Long): Boolean {
        val tag = RuleProviders.tag(url)
        val failed = values.getProperty("$tag.failed")?.toLongOrNull()
        if (failed != null && now - failed < CategoryLists.RETRY) return false
        val checked = values.getProperty("$tag.checked")?.toLongOrNull() ?: return true
        return file(url) == null || now - checked >= CategoryLists.DAY
    }

    /** Downloads, reads, checks and keeps the list at [url]. True when a new copy was put in place. */
    fun update(url: String, now: Long): Boolean {
        if (RuleProviders.clean(url) != url) return false
        val tag = RuleProviders.tag(url)
        val etag = synchronized(this) { values.getProperty("$tag.etag")?.takeIf { file(url) != null } }
        val fetched = runCatching { fetch(url, etag) }.getOrElse { failure ->
            fail(tag, now, (failure as? IOException)?.message ?: "Download failed")
            return false
        }
        if (fetched is ListDownload.Same) {
            synchronized(this) {
                values.setProperty("$tag.checked", "$now")
                values.remove("$tag.error"); values.remove("$tag.failed")
                save()
            }
            return false
        }
        val kept = RuleProviders.read((fetched as ListDownload.Got).bytes) ?: run {
            fail(tag, now, "Not a list KOne reads")
            return false
        }
        synchronized(this) {
            folder.mkdirs()
            val fresh = File(folder, "$tag.${kept.extension}.new")
            fresh.writeBytes(kept.bytes)
            if (!parses(fresh, kept.format)) {
                fresh.delete()
                fail(tag, now, "sing-box cannot read this list")
                return false
            }
            val target = File(folder, "$tag.${kept.extension}")
            if (!fresh.renameTo(target) && !(target.delete() && fresh.renameTo(target))) {
                fresh.delete()
                fail(tag, now, "Could not save the list")
                return false
            }
            // A list that changed kind leaves no copy of the other kind behind.
            values.getProperty("$tag.ext")?.takeIf { it != kept.extension }?.let { File(folder, "$tag.$it").delete() }
            values.setProperty("$tag.ext", kept.extension)
            values.setProperty("$tag.sha", CategoryLists.sha256(kept.bytes))
            values.setProperty("$tag.checked", "$now")
            val newTag = fetched.etag
            if (newTag != null) values.setProperty("$tag.etag", newTag) else values.remove("$tag.etag")
            values.remove("$tag.error"); values.remove("$tag.failed")
            save()
        }
        return true
    }

    /** Lets go of the lists no rule names any more. */
    @Synchronized
    fun keepOnly(urls: Set<String>) {
        val tags = urls.map(RuleProviders::tag).toSet()
        val gone = values.stringPropertyNames().map { it.substringBeforeLast('.') }.toSet() - tags
        if (gone.isEmpty()) return
        gone.forEach { tag ->
            if (RuleProviders.isTag(tag)) values.getProperty("$tag.ext")?.let { File(folder, "$tag.$it").delete() }
            listOf("sha", "ext", "checked", "etag", "error", "failed").forEach { values.remove("$tag.$it") }
        }
        save()
    }

    @Synchronized
    private fun fail(tag: String, now: Long, why: String) {
        values.setProperty("$tag.error", why)
        values.setProperty("$tag.failed", "$now")
        save()
    }

    private fun save() {
        runCatching {
            folder.mkdirs()
            index.outputStream().use { values.store(it, null) }
        }
    }
}
