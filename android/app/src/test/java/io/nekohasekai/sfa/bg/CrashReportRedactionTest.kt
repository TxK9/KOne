package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CrashReportRedactionTest {
    @Test fun bearerAndAssignmentValuesAreRemovedWithoutHidingStackFrames() {
        val input = "Transport failed: bEaReR synthetic-bearer\n" +
            "Caused by: credential=synthetic-credential, stage=connect\n" +
            "Suppressed: password='synthetic password with spaces'\n\tat example.Loader.read(Loader.kt:42)"
        val output = CrashReportRedaction.text(input)
        for (secret in listOf("synthetic-bearer", "synthetic-credential", "synthetic password with spaces")) assertFalse(output.contains(secret))
        for (context in listOf("Transport failed", "Caused by:", "Suppressed:", "stage=connect", "Loader.kt:42")) assertTrue(output.contains(context))
    }

    @Test fun quotedJsonSecretsAreRemovedAndOrdinaryFieldsRemainValid() {
        val input = JSONObject().put("password", "synthetic\"quoted secret").put("credential", "synthetic-credential")
            .put("stage", "connect").put("status", 403).toString()
        val output = CrashReportRedaction.text(input)
        assertFalse(output.contains("synthetic"))
        val json = JSONObject(output)
        assertEquals("[redacted]", json.getString("password"))
        assertEquals("[redacted]", json.getString("credential"))
        assertEquals("connect", json.getString("stage"))
        assertEquals(403, json.getInt("status"))
    }

    @Test fun authorizationAndCookieHeadersDoNotConsumeTheNextDiagnosticLine() {
        val input = "Authorization: Basic c3ludGhldGljOnNlY3JldA==\r\n" +
            "Cookie: session=synthetic-cookie; path=/\n" +
            "Proxy-Authorization: Bearer synthetic-proxy\nFailure: timeout after 15 seconds"
        val output = CrashReportRedaction.text(input)
        for (secret in listOf("c3ludGhldGljOnNlY3JldA==", "synthetic-cookie", "synthetic-proxy")) assertFalse(output.contains(secret))
        assertTrue(output.contains("Failure: timeout after 15 seconds"))
    }

    @Test fun httpUserInfoAndTokenQueryAreRemovedWhileTheFailureLocationRemains() {
        val input = "Request https://synthetic-user:synthetic-password@example.invalid/api?token=synthetic-token&status=retry failed"
        val output = CrashReportRedaction.text(input)
        for (secret in listOf("synthetic-user", "synthetic-password", "synthetic-token")) assertFalse(output.contains(secret))
        assertTrue(output.contains("example.invalid/api"))
        assertTrue(output.contains("status=retry failed"))
    }

    @Test fun unterminatedQuotedValuesDoNotExposeSecretsOrRemoveFrames() {
        for (input in listOf("password=\"synthetic unfinished secret\n\tat example.Loader.read(Loader.kt:42)",
            "\"credential\":\"synthetic unfinished secret\n\tat example.Loader.read(Loader.kt:42)")) {
            val output = CrashReportRedaction.text(input)
            assertFalse(output.contains("synthetic"))
            assertFalse(output.contains("unfinished secret"))
            assertTrue(output.contains("Loader.kt:42"))
        }
    }

    @Test fun benignDiagnosticsAndNonSecretFieldNamesRemainUnchanged() {
        val input = "Bitmap allocation failed at 320 x 720; tokenCount=2; passwordPolicy=strict; status=403"
        assertEquals(input, CrashReportRedaction.text(input))
    }

    @Test fun repeatedRedactionIsStable() {
        val first = CrashReportRedaction.text("Bearer synthetic-token; credential=synthetic-value https://user:pass@example.invalid")
        assertEquals(first, CrashReportRedaction.text(first))
    }
}
