package io.nekohasekai.sfa.bg

import java.io.InputStream
import java.io.OutputStream

/** Text-only export boundary; framework binaries and restorable configuration bypass it. */
internal object DiagnosticText {
    private const val MAX_LINE_CHARS = 32_768

    fun copy(input: InputStream, output: OutputStream): Long {
        val reader = input.reader(Charsets.UTF_8)
        val buffer = CharArray(4_096)
        val line = StringBuilder()
        var truncated = false
        var written = 0L
        fun emit(newline: Boolean) {
            val value = CrashReportRedaction.text(line.toString()) +
                (if (truncated) " [diagnostic line truncated]" else "") + (if (newline) "\n" else "")
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.write(bytes)
            written += bytes.size
            line.setLength(0)
            truncated = false
        }
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            for (index in 0 until read) {
                val character = buffer[index]
                if (character == '\n') emit(true)
                else if (line.length < MAX_LINE_CHARS) line.append(character)
                else truncated = true
            }
        }
        if (line.isNotEmpty() || truncated) emit(false)
        return written
    }
}
