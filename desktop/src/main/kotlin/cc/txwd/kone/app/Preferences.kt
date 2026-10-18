package cc.txwd.kone.app

import cc.txwd.kone.ui.Appearance
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
