package io.nekohasekai.sfa.bg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    private fun copy(text: String): String {
        val output = ByteArrayOutputStream()
        val source = object : FilterInputStream(text.byteInputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 3))
        }
        val count = DiagnosticText.copy(source, output)
        assertEquals(output.size().toLong(), count)
        return output.toString(Charsets.UTF_8)
    }

    @Test fun credentialSyntaxIsRedactedEvenWhenInputChunksSplitItsBoundaries() {
        val output = copy("Authorization: Bearer synthetic-stream-secret\r\ncredential=\"synthetic quoted value\"\nCaused by: timeout at Loader.kt:42")
        assertFalse(output.contains("synthetic"))
        assertTrue(output.contains("\r\n"))
        assertTrue(output.contains("Caused by: timeout at Loader.kt:42"))
    }

    @Test fun oversizedLinesHaveAnExplicitBoundAndDoNotLoseTheFollowingFrame() {
        val output = copy("password=\"" + "synthetic-".repeat(8_000) + "\"\n\tat example.Loader.read(Loader.kt:42)\n")
        assertFalse(output.contains("synthetic-"))
        assertTrue(output.contains("[diagnostic line truncated]"))
        assertTrue(output.length < 35_000)
        assertTrue(output.endsWith("Loader.kt:42)\n"))
    }

    @Test fun ordinaryUtf8DiagnosticsAndEmptyLinesRoundTrip() {
        val input = "Stage=connect café\r\n\nFailure at Loader.kt:42"
        assertEquals(input, copy(input))
    }

    @Test(timeout = 5_000) fun jsonSecretsStayRedactedWhenTheLineCapSplitsAnEscape() {
        val cap = 32_768
        val marker = "synthetic-boundary-secret "
        for (field in listOf("password", "credential")) {
            for (offset in -1..1) {
                val prefix = "{\"$field\":\""
                val filler = "x".repeat(cap - prefix.length - marker.length - 1 + offset)
                val input = prefix + marker + filler + "\\\"suffix\"}"
                assertTrue(JSONObject(input).getString(field).contains(marker))
                val output = copy(input + "\n\tat example.Loader.read(Loader.kt:42)\n")
                assertFalse("Secret retained at escape offset $offset", output.contains("synthetic-boundary-secret"))
                assertTrue(output.contains("\"$field\":\"[redacted]\""))
                assertTrue(output.contains("[diagnostic line truncated]"))
                assertTrue(output.endsWith("Loader.kt:42)\n"))
                assertTrue(output.length < 256)
            }
        }
    }

    @Test fun ordinaryQuotedFieldsKeepTheirRetainedPrefixAtTheSameBoundary() {
        val cap = 32_768
        val prefix = "{\"passwordPolicy\":\"ordinary-policy "
        val retained = prefix + "x".repeat(cap - prefix.length - 1) + "\\"
        assertEquals(cap, retained.length)
        val output = copy(retained + "\"suffix\"}\nNext diagnostic line\n")
        assertEquals(retained + " [diagnostic line truncated]\nNext diagnostic line\n", output)
        assertEquals(retained, copy(retained))
    }

    @Test fun frameworkBinaryCopyRemainsByteExact() {
        val root = Files.createTempDirectory("synthetic-framework").toFile()
        try {
            val bytes = byteArrayOf(0, 1, 2, -1, -128, 13, 10, 42)
            val file = File(root, "framework.jar").apply { writeBytes(bytes) }
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                val method = DebugInfoExporter::class.java.getDeclaredMethod("addFileEntry", ZipOutputStream::class.java,
                    File::class.java, String::class.java, List::class.java, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }
                assertTrue(method.invoke(DebugInfoExporter, zip, file, "framework/framework.jar", mutableListOf<String>(), true, false) as Boolean)
            }
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                assertEquals("framework/framework.jar", zip.nextEntry.name)
                assertArrayEquals(bytes, zip.readBytes())
            }
        } finally { root.deleteRecursively() }
    }
}
