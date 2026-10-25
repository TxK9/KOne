package io.nekohasekai.sfa.update

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/** Public update contract. It contains release policy, never executable commands. */
data class KoneRelease(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val bytes: Long,
    val minRecommendedVersionCode: Int,
    val minSdk: Int,
    val automatic: Boolean,
    val allowMetered: Boolean,
    val notes: String,
) {
    fun recommendedFor(installedCode: Int, verifiedPackageReady: Boolean): Boolean =
        verifiedPackageReady && versionCode > installedCode && installedCode < minRecommendedVersionCode

    fun toJson(): String = JSONObject()
        .put("updateFormat", 1).put("state", "ready").put("approvedForDownload", true)
        .put("channel", if (packageName == "cc.txwd.kone") "invite-client" else "owner")
        .put("applicationId", packageName).put("versionCode", versionCode).put("version", versionName)
        .put("url", downloadUrl).put("sha256", sha256).put("bytes", bytes)
        .put("minRecommendedVersionCode", minRecommendedVersionCode).put("minSdk", minSdk)
        .put("automaticUpdate", automatic).put("allowMeteredUpdate", allowMetered)
        .put("releaseNotes", notes).toString()

    companion object {
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        private val hashPattern = Regex("[0-9a-fA-F]{64}")
        private val apkPath = Regex("/downloads/KOne-[A-Za-z0-9._-]+\\.apk")

        fun parse(text: String, feedUrl: String, expectedPackage: String): KoneRelease? {
            require(text.length <= 65_536) { "Update metadata is too large" }
            val json = JSONObject(text)
            if (json.optInt("updateFormat") != 1 || json.optString("state") != "ready" ||
                json.opt("approvedForDownload") != true) return null
            require(json.getString("applicationId") == expectedPackage) { "Update package edition does not match" }
            val channel = if (expectedPackage == "cc.txwd.kone") "invite-client" else "owner"
            require(json.getString("channel") == channel) { "Update channel does not match" }
            val feed = URI(feedUrl)
            require(feed.scheme == "https" && feed.host != null && feed.userInfo == null && feed.fragment == null && feed.query == null)
            val url = feed.resolve(json.getString("url"))
            require(url.scheme == "https" && url.host.equals(feed.host, true) && port(url) == port(feed) &&
                url.userInfo == null && url.fragment == null && url.query == null && apkPath.matches(url.rawPath)) { "Update URL is not trusted" }
            val code = integer(json, "versionCode", 1, Int.MAX_VALUE.toLong()).toInt()
            val size = integer(json, "bytes", 1, MAX_APK_BYTES)
            val minimum = if (json.has("minRecommendedVersionCode")) integer(json, "minRecommendedVersionCode", 0, code.toLong()).toInt() else 0
            val sdk = if (json.has("minSdk")) integer(json, "minSdk", 21, 1000).toInt() else 24
            val hash = json.getString("sha256")
            require(hashPattern.matches(hash)) { "Update checksum is invalid" }
            val name = json.getString("version").trim()
            require(name.isNotEmpty() && name.length <= 40 && name.none { it.isISOControl() })
            return KoneRelease(expectedPackage, code, name, url.toASCIIString(), hash.lowercase(), size, minimum, sdk,
                boolean(json, "automaticUpdate", true), boolean(json, "allowMeteredUpdate", false), json.optString("releaseNotes").take(500))
        }

        private fun port(uri: URI): Int = if (uri.port == -1) 443 else uri.port
        private fun integer(json: JSONObject, key: String, min: Long, max: Long): Long {
            val raw = json.get(key)
            require(raw is Number)
            return requireNotNull(raw.toString().toLongOrNull()).also { require(it in min..max) }
        }
        private fun boolean(json: JSONObject, key: String, default: Boolean): Boolean {
            if (!json.has(key)) return default
            return json.get(key).also { require(it is Boolean) } as Boolean
        }
    }
}

internal object KonePackageChecks {
    data class Identity(val packageName: String, val versionCode: Long, val signers: Set<String>)

    fun requireIdentity(installed: Identity, candidate: Identity, release: KoneRelease) {
        require(candidate.packageName == installed.packageName && candidate.packageName == release.packageName) { "Update package name does not match" }
        require(candidate.versionCode == release.versionCode.toLong() && candidate.versionCode > installed.versionCode) { "Update build number does not match" }
        require(installed.signers.isNotEmpty() && candidate.signers == installed.signers) { "Update package signature does not match" }
    }

    fun copyAndVerify(input: InputStream, output: OutputStream, release: KoneRelease, progress: (Long) -> Unit = {}) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= release.bytes && count <= KoneRelease.MAX_APK_BYTES) { "Update package size does not match" }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            progress(count)
        }
        require(count == release.bytes && hex(digest.digest()) == release.sha256) { "Update package verification failed" }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
