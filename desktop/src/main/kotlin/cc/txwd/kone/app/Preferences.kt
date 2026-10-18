package cc.txwd.kone.app

import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.UserRule
import cc.txwd.kone.core.UserRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Backdrop
import java.io.File
import java.util.Properties

/** The person's choices that are KOne's own to keep, in settings.properties in the App's folder. */
class Preferences(folder: File) {
    private val file = File(folder, "settings.properties")
    private val values = Properties().apply { runCatching { file.inputStream().use(::load) } }

    var appearance: Appearance
        get() = Appearance.entries.firstOrNull { it.name == values.getProperty("appearance") } ?: Appearance.System
        set(value) {
            values.setProperty("appearance", value.name)
            save()
        }

    /** What fills the dashboard behind the nodes (Settings → Background). */
    var backdrop: Backdrop
        get() = Backdrop.entries.firstOrNull { it.name == values.getProperty("backdrop") } ?: Backdrop.Ink
        set(value) {
            values.setProperty("backdrop", value.name)
            save()
        }

    /** Whether the cities' wind, rain and sea are heard (Settings → Ambient sound); off until the person turns it on. */
    var sound: Boolean
        get() = values.getProperty("sound") == "true"
        set(value) {
            values.setProperty("sound", value.toString())
            save()
        }

    /** Whether Ctrl+Alt+K turns the connection on and off (Settings → Keyboard shortcut); off until the person turns it on. */
    var hotkey: Boolean
        get() = values.getProperty("hotkey") == "true"
        set(value) {
            values.setProperty("hotkey", value.toString())
            save()
        }

    /** How KOne connects: Adapter (its own, TUN) or Proxy (Windows' proxy setting); Tools → Connection method. */
    var way: String
        get() = values.getProperty("way")?.takeIf { it == AppModel.WAY_PROXY } ?: AppModel.WAY_ADAPTER
        set(value) {
            values.setProperty("way", value)
            save()
        }

    /** The core's mode: Rule, Global or Direct (Tools → Mode). */
    var mode: String
        get() = values.getProperty("mode")?.takeIf { it in KoneConfig.MODES } ?: "Rule"
        set(value) {
            values.setProperty("mode", value)
            save()
        }

    /** The programs sent straight out, by file name (Tools → Direct apps). */
    var bypass: List<String>
        get() = values.getProperty("bypass")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) {
            values.setProperty("bypass", KoneConfig.programs(value).joinToString("|"))
            save()
        }

    /** The sites sent straight out in every mode (Tools → Direct sites). */
    var direct: List<String>
        get() = values.getProperty("direct")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) {
            values.setProperty("direct", KoneConfig.sites(value).joinToString("|"))
            save()
        }

    /** The sites through the node in Rule too (Tools → Proxied sites). */
    var proxied: List<String>
        get() = values.getProperty("proxied")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) {
            values.setProperty("proxied", KoneConfig.sites(value).joinToString("|"))
            save()
        }

    /** The person's own routing rules, in order (Rules), kept as they are checked. */
    var rules: List<UserRule>
        get() = values.getProperty("rules")?.let { text ->
            runCatching { (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(UserRule::fromJson) } }.getOrNull()
        }.orEmpty()
        set(value) {
            values.setProperty("rules", JsonArray(UserRules.clean(value).map { it.toJson() }).toString())
            save()
        }

    /** Whether ads are refused (Rules → Block ads); off until the person turns it on. */
    var blockAds: Boolean
        get() = values.getProperty("blockAds") == "true"
        set(value) {
            values.setProperty("blockAds", value.toString())
            save()
        }

    /** Whether KOne was connected when it last went away without the person ending it (Windows shutting down). */
    var connected: Boolean
        get() = values.getProperty("connected") == "true"
        set(value) {
            if (value) values.setProperty("connected", "true") else values.remove("connected")
            save()
        }

    /** Where the window was last, in the screen's units, and whether it filled the screen. */
    var window: WindowPlace?
        get() = values.getProperty("window")?.split(',')?.let { parts ->
            val numbers = parts.take(4).map { it.trim().toIntOrNull() ?: return@let null }
            if (numbers.size < 4) null else WindowPlace(numbers[0], numbers[1], numbers[2], numbers[3], parts.getOrNull(4)?.trim() == "max")
        }
        set(value) {
            if (value == null) values.remove("window")
            else values.setProperty("window", "${value.x},${value.y},${value.width},${value.height}${if (value.maximized) ",max" else ""}")
            save()
        }

    private fun save() {
        runCatching { file.outputStream().use { values.store(it, null) } }
    }
}

data class WindowPlace(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)
