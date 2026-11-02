package io.nekohasekai.sfa.bg

/** Remove recognized credential syntax from application-owned JVM crash text, not every private value. */
internal object CrashReportRedaction {
    private const val KEYS = "(?:password|passwd|credential|invite[_-]?code|access[_-]?token|refresh[_-]?token|client[_-]?secret|private[_-]?key|token|secret|psk|authorization|proxy-authorization|cookie|set-cookie)"
    private val jsonValue = Regex("""(?im)("$KEYS"\s*:\s*)("(?:\\.|[^"\\\r\n])*(?:"|(?=\r?$)))""")
    private val assignment = Regex("""(?im)(\b$KEYS[ \t]*=[ \t]*)("(?:\\.|[^"\\\r\n])*(?:"|(?=\r?$))|'(?:\\.|[^'\\\r\n])*(?:'|(?=\r?$))|[^\s,;&}]+)""")
    private val header = Regex("""(?im)(\b(?:authorization|proxy-authorization|cookie|set-cookie)[ \t]*:[ \t]*)[^\r\n]*""")
    private val bearer = Regex("""(?i)(\bBearer[ \t]+)[^\s"'<>]+""")
    private val userInfo = Regex("""(?i)\b(https?://)[^/\s?#@]+@""")

    fun text(value: String): String {
        var result = jsonValue.replace(value) { it.groupValues[1] + "\"[redacted]\"" }
        result = assignment.replace(result) { it.groupValues[1] + "[redacted]" }
        result = header.replace(result) { it.groupValues[1] + "[redacted]" }
        result = bearer.replace(result) { it.groupValues[1] + "[redacted]" }
        return userInfo.replace(result) { it.groupValues[1] + "[redacted]@" }
    }
}
