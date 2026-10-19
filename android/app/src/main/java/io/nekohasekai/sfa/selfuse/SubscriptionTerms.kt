package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.UserInfo
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Each remote profile's provider terms as kept (one JSON object by profile id) and said on its row. */
object SubscriptionTerms {
    /** [all] (as kept) with [profileId]'s terms set to [info], or removed when null. */
    fun with(all: String, profileId: Long, info: UserInfo?): String {
        val json = runCatching { JSONObject(all) }.getOrDefault(JSONObject())
        if (info == null) {
            json.remove(profileId.toString())
        } else {
            json.put(
                profileId.toString(),
                JSONObject().put("upload", info.upload).put("download", info.download).put("total", info.total)
                    .apply { info.expire?.let { put("expire", it) } },
            )
        }
        return json.toString()
    }

    fun read(all: String, profileId: Long): UserInfo? {
        val item = runCatching { JSONObject(all).optJSONObject(profileId.toString()) }.getOrNull() ?: return null
        return UserInfo(item.optLong("upload"), item.optLong("download"), item.optLong("total"), item.optLong("expire").takeIf { it > 0 })
    }

    /**
     * The terms in one line for [name]'s row, and whether it is a warning: the warning when there
     * is one (ended, ending within three days, nine tenths used), else what is used and until when.
     */
    fun summary(name: String, info: UserInfo, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        info.warning(name, now)?.let { return it to true }
        val until = info.expire?.let { "until " + Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        return listOfNotNull(info.usage(), until).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { it to false }
    }
}
