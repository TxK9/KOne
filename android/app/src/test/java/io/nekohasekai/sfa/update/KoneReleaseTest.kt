package io.nekohasekai.sfa.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class KoneReleaseTest {
    private val feed = "https://kone.qingwanli.com/release.json"
    private val bytes = "a signed APK fixture would go here".toByteArray()
    private val hash = KonePackageChecks.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun manifest() = JSONObject().put("updateFormat", 1).put("state", "ready").put("approvedForDownload", true)
        .put("channel", "invite-client").put("applicationId", "cc.txwd.kone").put("versionCode", 10017)
        .put("version", "1.0.0").put("url", "downloads/KOne-client-1.0.0-b10017-arm64-v8a.apk")
        .put("sha256", hash).put("bytes", bytes.size).put("minRecommendedVersionCode", 10017)
    private fun parse(json: JSONObject = manifest()) = KoneRelease.parse(json.toString(), feed, "cc.txwd.kone")!!
    private fun rejected(json: JSONObject) { assertTrue(runCatching { parse(json) }.isFailure) }

    @Test fun resolvesThePublishedDownloadAndKeepsPolicyOnCacheRoundTrip() {
        val release = parse()
        assertEquals("https://kone.qingwanli.com/downloads/KOne-client-1.0.0-b10017-arm64-v8a.apk", release.downloadUrl)
        assertEquals(release, KoneRelease.parse(release.toJson(), feed, "cc.txwd.kone"))
        assertTrue(release.automatic)
        assertFalse(release.allowMetered)
    }

    @Test fun doesNotFollowCrossOriginInsecureOrEncodedPaths() {
        listOf("https://evil.example/downloads/KOne-client.apk", "http://kone.qingwanli.com/downloads/KOne-client.apk",
            "https://user@kone.qingwanli.com/downloads/KOne-client.apk", "/downloads/%2e%2e/KOne-client.apk",
            "/downloads/KOne-client.apk?token=abc", "//evil.example/downloads/KOne-client.apk",
            "https://kone.qingwanli.com:444/downloads/KOne-client.apk").forEach { rejected(manifest().put("url", it)) }
    }

    @Test fun rejectsWrongEditionInvalidHashesAndContradictoryMinimums() {
        rejected(manifest().put("applicationId", "cc.txwd.tianxiawudi.proxy"))
        rejected(manifest().put("channel", "owner"))
        rejected(manifest().put("sha256", "bad"))
        rejected(manifest().put("bytes", KoneRelease.MAX_APK_BYTES + 1))
        rejected(manifest().put("minRecommendedVersionCode", 10018))
        rejected(manifest().put("versionCode", 10017.5))
        rejected(manifest().put("automaticUpdate", "true"))
    }

    @Test fun withdrawnOrOldFormatReleasesDoNotForceAnUpdate() {
        assertNull(KoneRelease.parse(manifest().put("state", "draft").toString(), feed, "cc.txwd.kone"))
        assertNull(KoneRelease.parse(manifest().put("approvedForDownload", false).toString(), feed, "cc.txwd.kone"))
        assertNull(KoneRelease.parse(manifest().put("updateFormat", 2).toString(), feed, "cc.txwd.kone"))
    }

    @Test fun minimumOnlyAppliesWhenANewerVerifiedPackageIsReady() {
        val release = parse()
        assertFalse(release.recommendedFor(10016, false))
        assertTrue(release.recommendedFor(10016, true))
        assertFalse(release.recommendedFor(10017, true))
        assertFalse(release.copy(minRecommendedVersionCode = 10016).recommendedFor(10016, true))
    }

    @Test fun rejectsTruncatedOversizedAndCorruptedDownloads() {
        val release = parse()
        val output = ByteArrayOutputStream()
        KonePackageChecks.copyAndVerify(ByteArrayInputStream(bytes), output, release)
        assertArrayEquals(bytes, output.toByteArray())
        for (wrong in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), bytes.copyOf().apply { this[0] = 0 })) {
            assertTrue(runCatching { KonePackageChecks.copyAndVerify(ByteArrayInputStream(wrong), ByteArrayOutputStream(), release) }.isFailure)
        }
    }

    @Test fun onlyTheSamePackageAndSignerCanMoveForwardToTheAnnouncedBuild() {
        val installed = KonePackageChecks.Identity("cc.txwd.kone", 10016, setOf("trusted-certificate"))
        val valid = installed.copy(versionCode = 10017)
        KonePackageChecks.requireIdentity(installed, valid, parse())
        for (wrong in listOf(valid.copy(packageName = "cc.txwd.tianxiawudi.proxy"), valid.copy(signers = setOf("other-certificate")),
            valid.copy(signers = emptySet()), valid.copy(versionCode = 10016), valid.copy(versionCode = 10018))) {
            assertTrue(runCatching { KonePackageChecks.requireIdentity(installed, wrong, parse()) }.isFailure)
        }
    }
}
