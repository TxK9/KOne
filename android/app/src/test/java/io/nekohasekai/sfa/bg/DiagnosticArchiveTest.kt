package io.nekohasekai.sfa.bg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic diagnostic files only; never reads a device report, configuration or system command. */
class DiagnosticArchiveTest {
    private fun withReport(check: (CrashReport, File) -> Unit) {
        val root = Files.createTempDirectory("synthetic-diagnostic").toFile()
        try {
            val source = File(root, "original").apply { mkdirs() }
            File(source, "metadata.json").writeText(JSONObject().put("exceptionName", "example.Failure")
                .put("exceptionReason", "Authorization: Bearer synthetic-old-secret").put("appVersion", "10056").toString())
            File(source, "jvm.log").writeText("credential=synthetic-old-jvm\n\tat example.Loader.read(Loader.kt:42)")
            File(source, "go.log").writeText("password=synthetic-old-go stage=connect")
            File(source, "configuration.json").writeText("""{"outbounds":[{"password":"synthetic-restorable-node"}]}""")
            File(source, ".read").writeText("")
            check(CrashReport("synthetic-report", Date(0), source, true), File(root, "staged"))
        } finally { root.deleteRecursively() }
    }

    @Test fun metadataOnlyExportRedactsHistoricalReasonAndOmitsOptOutFiles() = withReport { report, output ->
        CrashReportManager.prepareArchiveFiles(report, output, includeConfig = false, includeLog = false)
        assertEquals(setOf("metadata.json"), output.list()!!.toSet())
        val json = JSONObject(File(output, "metadata.json").readText())
        assertFalse(json.toString().contains("synthetic-old-secret"))
        assertEquals("example.Failure", json.getString("exceptionName"))
        assertEquals("10056", json.getString("appVersion"))
        assertTrue(File(report.directory, "metadata.json").readText().contains("synthetic-old-secret"))
    }

    @Test fun includedDiagnosticLogsAreRedactedWithoutRewritingOriginals() = withReport { report, output ->
        CrashReportManager.prepareArchiveFiles(report, output, includeConfig = false, includeLog = true)
        assertFalse(File(output, "jvm.log").readText().contains("synthetic-old-jvm"))
        assertFalse(File(output, "go.log").readText().contains("synthetic-old-go"))
        assertTrue(File(output, "jvm.log").readText().contains("Loader.kt:42"))
        assertTrue(File(output, "go.log").readText().contains("stage=connect"))
        assertTrue(File(report.directory, "jvm.log").readText().contains("synthetic-old-jvm"))
    }

    @Test fun explicitConfigurationInclusionPreservesTheRestorableBytes() = withReport { report, output ->
        CrashReportManager.prepareArchiveFiles(report, output, includeConfig = true, includeLog = false)
        assertArrayEquals(File(report.directory, "configuration.json").readBytes(), File(output, "configuration.json").readBytes())
        assertTrue(File(output, "configuration.json").readText().contains("synthetic-restorable-node"))
    }

    @Test fun malformedMetadataIsReplacedByAnExplicitSafeDiagnostic() = withReport { report, output ->
        File(report.directory, "metadata.json").writeText("not-json password=synthetic-malformed-secret")
        CrashReportManager.prepareArchiveFiles(report, output, includeConfig = false, includeLog = false)
        val json = JSONObject(File(output, "metadata.json").readText())
        assertEquals("Invalid diagnostic metadata", json.getString("error"))
        assertFalse(json.toString().contains("synthetic-malformed-secret"))
    }

    private fun debugText(text: String): String {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            DebugInfoExporter::class.java.getDeclaredMethod("addTextEntry", ZipOutputStream::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(DebugInfoExporter, zip, "logs/service_logs.txt", text)
        }
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            assertEquals("logs/service_logs.txt", zip.nextEntry.name)
            zip.readBytes().decodeToString()
        }
    }

    @Test fun generatedDebugTextExcludesRecognizedCredentialsAndKeepsContext() {
        val result = debugText("Authorization: Bearer synthetic-debug-secret\nFailure stage=connect\ncredential=synthetic-debug-credential")
        assertFalse(result.contains("synthetic-debug-secret"))
        assertFalse(result.contains("synthetic-debug-credential"))
        assertTrue(result.contains("Failure stage=connect"))
    }

    @Test fun ordinaryDebugTextIsNotModified() {
        val text = "ERROR[2026-11-02 00:00:00] [Synthetic]: timeout at Loader.kt:42"
        assertEquals(text, debugText(text))
    }
}
