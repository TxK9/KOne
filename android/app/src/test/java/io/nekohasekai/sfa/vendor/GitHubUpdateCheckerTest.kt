package io.nekohasekai.sfa.vendor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test fun upstreamFeedOnlyServesItsOwnExactPackage() {
        assertTrue(GitHubUpdateChecker.servesPackage("io.nekohasekai.sfa"))
        listOf("cc.txwd.kone", "cc.txwd.tianxiawudi.proxy", "io.nekohasekai.sfa.debug", "", "IO.NEKO HASEKAI.SFA").forEach {
            assertFalse(GitHubUpdateChecker.servesPackage(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun clientCannotUseTheUpstreamFallback() = GitHubUpdateChecker.requireUpstreamPackage("cc.txwd.kone")

    @Test(expected = IllegalArgumentException::class)
    fun ownerCannotUseTheUpstreamFallback() = GitHubUpdateChecker.requireUpstreamPackage("cc.txwd.tianxiawudi.proxy")
}
