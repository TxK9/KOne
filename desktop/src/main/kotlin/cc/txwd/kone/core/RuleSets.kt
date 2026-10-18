package cc.txwd.kone.core

import java.io.File
import java.security.MessageDigest

/**
 * The rule sets the template routes by, carried inside the App so the first connection does not
 * wait on a download: the China ones (the phone's own copies) and, for Block ads, v2fly's
 * category-ads-all. The core refreshes them later itself. Sources: licenses/rule-sets.txt.
 */
object RuleSets {
    private val expected = mapOf(
        "geosite-cn.srs" to "2f4af0be08f2ff494a8fcdef992dc92a2267600ce26c9bba17421d1ce9a6715c",
        "geoip-cn.srs" to "ebee603fdf402314b44b9f653cdcf6d9cc9c41e84e2b3515e3123c5c920a93bc",
        "geosite-category-ads-all.srs" to "84e2ed79617a492cb1adaf28bf0c59a1a42af854ab935605ba84e0deba386c88",
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Puts the rule sets in [directory] unless they are already there intact, and returns its path. */
    fun ensure(directory: File): String {
        directory.mkdirs()
        expected.forEach { (name, hash) ->
            val target = File(directory, name)
            if (!target.isFile || sha256(target.readBytes()) != hash) {
                val data = checkNotNull(RuleSets::class.java.getResourceAsStream("/rules/$name")) { "missing $name" }.use { it.readBytes() }
                check(sha256(data) == hash) { "Built-in routing rules failed verification" }
                val temporary = File(directory, "$name.tmp")
                temporary.writeBytes(data)
                check(temporary.renameTo(target) || (target.delete() && temporary.renameTo(target))) { "Unable to save built-in routing rules" }
            }
        }
        return directory.absolutePath.replace('\\', '/')
    }
}
