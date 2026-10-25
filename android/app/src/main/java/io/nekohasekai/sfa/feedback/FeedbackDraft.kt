package io.nekohasekai.sfa.feedback

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** A draft and its immutable retry payload belong to one binding, never another account. */
class FeedbackDraft(context: Context, endpoint: String, credential: String) {
    private val prefs = context.getSharedPreferences("kone_feedback_drafts", Context.MODE_PRIVATE)
    private val key = bindingKey(endpoint, credential)
    val description: String get() = synchronized(lock) { prefs.getString("${key}_description", "").orEmpty() }
    val category: String get() = synchronized(lock) { prefs.getString("${key}_category", "other").orEmpty() }

    val images: org.json.JSONArray get() = synchronized(lock) { org.json.JSONArray(prefs.getString("${key}_images", "[]")) }
    val receipt: Pair<String, JSONObject>? get() = synchronized(lock) {
        runCatching {
            prefs.getString("${key}_receipt", null)?.let {
                val value = JSONObject(it)
                value.getString("number") to value.getJSONObject("metadata")
            }
        }.getOrNull()
    }

    fun saveImages(images: org.json.JSONArray) = synchronized(lock) {
        prefs.edit().putString("${key}_images", images.toString()).remove("${key}_payload").commit()
    }

    fun save(category: String, description: String) = synchronized(lock) {
        if (category == this.category && description == this.description) return@synchronized
        prefs.edit().putString("${key}_category", category).putString("${key}_description", description)
            .remove("${key}_payload").apply()
    }

    fun payload(metadata: JSONObject): JSONObject = synchronized(lock) {
        prefs.getString("${key}_payload", null)?.let { return@synchronized JSONObject(it) }
        require(description.trim().length <= 4000 && (description.isNotBlank() || images.length() > 0))
        require(category in setOf("connection", "speed", "qr", "display", "other"))
        val value = JSONObject(metadata.toString()).put("requestId", UUID.randomUUID().toString())
            .put("description", description.trim()).put("images", images)
        check(prefs.edit().putString("${key}_payload", value.toString()).commit()) { "Cannot save the feedback draft" }
        value
    }

    /** A completed older upload must not erase edits made after its screen was closed. */
    fun complete(number: String, payload: JSONObject): Boolean = synchronized(lock) {
        require(number.matches(Regex("FB-[0-9]{6,20}")))
        val metadata = JSONObject()
        for (field in listOf("appVersion", "versionCode", "androidVersion", "manufacturer", "model")) {
            if (payload.has(field)) metadata.put(field, payload.get(field))
        }
        val editor = prefs.edit().putString("${key}_receipt", JSONObject()
            .put("number", number).put("metadata", metadata).toString())
        val current = prefs.getString("${key}_payload", null)?.let { JSONObject(it) }
        if (current != null && current.optString("requestId") == payload.getString("requestId")) {
            editor.remove("${key}_category").remove("${key}_description").remove("${key}_payload").remove("${key}_images")
        }
        // Persist the receipt and matching draft cleanup together. The caller still treats
        // an acknowledged server submission as successful if local persistence fails.
        editor.commit()
    }

    fun clearReceipt() = synchronized(lock) { prefs.edit().remove("${key}_receipt").commit() }

    fun clear() = synchronized(lock) {
        prefs.edit().remove("${key}_category").remove("${key}_description").remove("${key}_payload").remove("${key}_images").commit()
    }

    companion object {
        // Preferences can be opened by a newly composed screen while an old blocking
        // upload is finishing. Keep request checks and writes atomic across instances.
        private val lock = Any()
        fun bindingKey(endpoint: String, credential: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$endpoint\n$credential".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
