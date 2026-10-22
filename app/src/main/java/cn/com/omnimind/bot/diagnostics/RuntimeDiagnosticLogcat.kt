package cn.com.omnimind.bot.diagnostics

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import java.security.MessageDigest
import java.math.BigDecimal

/** Parses logcat's epoch format. The caller restricts logd to the application's UID. */
internal object RuntimeDiagnosticLogcat {
    private val linePattern = Regex("^\\s*(\\d+\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.+?)\\s*:\\s?(.*)$")
    fun parse(line: String): RuntimeLogEntry? {
        val match = linePattern.matchEntire(line) ?: return null
        val (timestamp, pid, tid, priority, tag, message) = match.destructured
        val id = MessageDigest.getInstance("SHA-256").digest(line.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return RuntimeLogEntry(
            id = "logcat-$id", createdAt = BigDecimal(timestamp).movePointRight(3).toLong(),
            level = when (priority) {
                "V" -> "VERBOSE"; "D" -> "DEBUG"; "I" -> "INFO"; "W" -> "WARN"
                "E" -> "ERROR"; else -> "ASSERT"
            },
            tag = tag, message = "[pid=$pid tid=$tid] $message", isCrash = priority == "F",
        )
    }
}
