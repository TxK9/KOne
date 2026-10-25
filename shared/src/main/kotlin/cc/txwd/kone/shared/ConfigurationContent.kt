package cc.txwd.kone.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Formatting and object-key order do not change a configuration; rule and node order do. */
object ConfigurationContent {
    fun same(first: String, second: String): Boolean {
        if (first == second) return true
        return runCatching {
            val before = Json.parseToJsonElement(first)
            val after = Json.parseToJsonElement(second)
            before is JsonObject && after is JsonObject && before == after
        }.getOrDefault(false)
    }
}
