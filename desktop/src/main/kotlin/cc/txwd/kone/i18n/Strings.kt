package cc.txwd.kone.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import cc.txwd.kone.shared.Countries

enum class Language(val id: String, val label: String) {
    System("system", "System default"),
    SimplifiedChinese("zh-CN", "简体中文"),
    English("en", "English");

    companion object {
        fun of(id: String?): Language = entries.firstOrNull { it.id == id } ?: System
        fun isChinese(language: Language, locale: Locale): Boolean = when (language) {
            SimplifiedChinese -> true
            English -> false
            System -> locale.language == "zh" && (locale.script == "Hans" || locale.country !in setOf("TW", "HK", "MO"))
        }
    }
}

/** English message IDs are the fallback. Only developer-owned UI messages call [tr]. */
object Strings {
    var language by mutableStateOf(Language.System)
    val chinese: Boolean get() = Language.isChinese(language, Locale.getDefault(Locale.Category.DISPLAY))
    val translations: Map<String, String> by lazy {
        val text = checkNotNull(Strings::class.java.getResourceAsStream("/i18n/zh-CN.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        (Json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }

    fun text(message: String, arguments: List<Any?> = emptyList(), chinese: Boolean = this.chinese): String {
        val pattern = if (chinese) translations[message] ?: message else message
        // Replace placeholders once: inserted user data is never translated or interpreted.
        return Regex("\\{(\\d+)\\}").replace(pattern) { match ->
            arguments.getOrNull(match.groupValues[1].toInt())?.toString() ?: match.value
        }
    }
}

fun tr(message: String, vararg arguments: Any?): String = Strings.text(message, arguments.toList())

/** Captions only: country IDs and provider-owned node names remain unchanged. */
fun countryTitle(label: String): String {
    if (!Strings.chinese) return label
    return Countries.all.firstOrNull { it.en == label || it.code == label }?.zh ?: tr(label)
}
