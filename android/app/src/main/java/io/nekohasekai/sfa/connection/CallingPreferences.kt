package io.nekohasekai.sfa.connection

import android.content.Context
import org.json.JSONObject

/** Per-profile local choices; contains no carrier identity, GPS position, server or credential. */
internal class CallingPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("kone_connection_choices",Context.MODE_PRIVATE)
    fun read(profileId: Long): CallingOptions = synchronized(lock) { runCatching {
        val json = JSONObject(preferences.getString(profileId.toString(),"{}").orEmpty())
        CallingOptions(json.optBoolean("enabled"), CallingDns.entries.firstOrNull { it.name == json.optString("dns") } ?: CallingDns.Original,
            json.optInt("mtu",0), json.optBoolean("videoTcp",false))
    }.getOrDefault(CallingOptions()) }
    fun write(profileId: Long, value: CallingOptions) = synchronized(lock) {
        require(profileId > 0)
        val json=JSONObject().put("enabled",value.enabled).put("dns",value.dns.name).put("mtu",value.mtu).put("videoTcp",value.videoTcp)
        check(preferences.edit().putString(profileId.toString(),json.toString()).commit()) { "Could not save settings" }
    }

    fun compareAndSet(profileId: Long, expected: CallingOptions, replacement: CallingOptions): Boolean = synchronized(lock) {
        require(profileId > 0)
        if (read(profileId) != expected) return@synchronized false
        write(profileId, replacement)
        true
    }

    private companion object { val lock = Any() }
}
