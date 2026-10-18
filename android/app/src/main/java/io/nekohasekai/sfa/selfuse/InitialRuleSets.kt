package io.nekohasekai.sfa.selfuse

import android.content.Context
import java.io.File
import java.security.MessageDigest

object InitialRuleSets {
    private val expected = mapOf(
        "geosite-cn.srs" to "2f4af0be08f2ff494a8fcdef992dc92a2267600ce26c9bba17421d1ce9a6715c",
        "geoip-cn.srs" to "ebee603fdf402314b44b9f653cdcf6d9cc9c41e84e2b3515e3123c5c920a93bc",
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun ensure(context: Context): String {
        val directory = File(context.filesDir, "initial-rules").also { it.mkdirs() }
        expected.forEach { (name, hash) ->
            val target = File(directory, name)
            if (!target.isFile || sha256(target.readBytes()) != hash) {
                val data = context.assets.open("selfuse-rules/$name").use { it.readBytes() }
                require(sha256(data) == hash) { "Built-in rule-set verification failed" }
                val temporary = File(directory, "$name.tmp")
                temporary.writeBytes(data)
                require(temporary.renameTo(target)) { "Cannot save the built-in rule set" }
            }
        }
        return directory.absolutePath
    }
}
