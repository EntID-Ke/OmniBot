package cn.com.omnimind.bot.diagnostics

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import com.google.gson.Gson
import java.net.URI

/** Export boundary for the existing app log store; never reads credential files. */
internal object RuntimeDiagnosticReport {
    private val gson = Gson()
    private val secretField = Regex(
        """(?i)(["']?(?:[a-z_]*api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|device[_-]?code|user[_-]?code|code[_-]?verifier|authorization[_-]?code|client[_-]?secret|private[_-]?key|token|authorization|password|passwd|secret|cookie|set-cookie)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)"""
    )

    fun redact(text: String): String = text
        .replace(Regex("(?im)^\\s*(?:Cookie|Set-Cookie|Authorization)\\s*[:=].*$"), "[AUTH HEADER REMOVED]")
        .replace(Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"), "[PRIVATE KEY REMOVED]")
        .replace(Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+"), "[AUTH REMOVED]")
        .replace(secretField) { "${it.groupValues[1]}[REDACTED]" }
        .replace(Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), "[JWT REMOVED]")
        .replace(Regex("sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}"), "[API KEY REMOVED]")
        .replace(Regex("https?://[^\\s<>\\\"']+")) { match ->
            runCatching {
                val uri = URI(match.value)
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
            }.getOrDefault("[URL REMOVED]")
        }
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL REMOVED]")

    fun encode(
        sessionId: String,
        device: Map<String, Any>,
        logs: List<RuntimeLogEntry>,
        now: Long = System.currentTimeMillis(),
    ): String {
        val entries = logs.take(100).map { log ->
            log.copy(
                tag = redact(log.tag).take(128),
                message = redact(log.message).take(2_048),
                stackTrace = log.stackTrace?.let(::redact)?.take(8_192),
            ).toMap()
        }.toMutableList()
        while (true) {
            val json = gson.toJson(linkedMapOf(
                "schemaVersion" to 1,
                "sessionId" to sessionId,
                "capturedAt" to now,
                "device" to device,
                "logs" to entries,
                "omittedLogCount" to logs.size - entries.size,
            ))
            if (json.toByteArray(Charsets.UTF_8).size <= 1_048_576) return json
            entries.removeAt(entries.lastIndex)
        }
    }
}
