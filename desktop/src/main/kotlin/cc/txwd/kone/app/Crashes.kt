package cc.txwd.kone.app

import java.io.File

/**
 * KOne stopping on an error it did not expect, kept on this computer only (crashes.log in the
 * App's folder): when, what and where, and the full trace for whoever helps. Nothing is sent
 * anywhere. The diagnosis report carries the last one's summary, never its message, which could
 * hold a subscription's link.
 */
object Crashes {
    /** A stop: when, and what and where in a few words ("NullPointerException in AppModel.refresh"). */
    data class Stop(val at: Long, val what: String)

    private const val NAME = "crashes.log"
    private const val MARK = "=== "

    /** The log is cut to about this many bytes, oldest first. */
    private const val KEEP = 64 * 1024

    fun record(failure: Throwable, folder: File = Places.data, now: Long = System.currentTimeMillis()) {
        runCatching {
            val file = File(folder, NAME)
            val entry = buildString {
                appendLine("$MARK$now ${summary(failure)}")
                appendLine("KOne ${Places.version}, ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
                appendLine(failure.stackTraceToString().trimEnd())
            }
            val before = if (file.isFile) file.readText() else ""
            var text = before + entry
            if (text.length > KEEP) {
                // Whole entries only: from the first mark past the cut.
                val cut = text.indexOf("\n$MARK", text.length - KEEP)
                text = if (cut >= 0) text.substring(cut + 1) else entry
            }
            folder.mkdirs()
            file.writeText(text)
        }
    }

    /** The latest stop, if any. */
    fun last(folder: File = Places.data): Stop? {
        val file = File(folder, NAME)
        if (!file.isFile) return null
        val header = runCatching { file.readLines() }.getOrNull()?.lastOrNull { it.startsWith(MARK) } ?: return null
        val rest = header.removePrefix(MARK)
        val at = rest.substringBefore(' ').toLongOrNull() ?: return null
        return Stop(at, rest.substringAfter(' ', ""))
    }

    /** The innermost cause's kind and the first frame of KOne's own code it passed through. */
    fun summary(failure: Throwable): String {
        var root = failure
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val ours = (root.stackTrace + failure.stackTrace).firstOrNull { it.className.startsWith("cc.txwd.kone.") }
        val where = ours?.let { " in ${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}" }.orEmpty()
        return (root::class.simpleName ?: "Error") + where
    }
}
