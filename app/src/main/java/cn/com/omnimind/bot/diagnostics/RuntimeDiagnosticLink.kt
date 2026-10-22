package cn.com.omnimind.bot.diagnostics

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

/** Validates a private connection link. The Activity imports it into the uploader. */
internal object RuntimeDiagnosticLink {
    private var pending: Map<String, String>? = null
    private var receivedAt = 0L

    internal fun parse(value: String): Map<String, String>? = runCatching {
        require(value.length <= 4096)
        val uri = URI(value)
        require(uri.scheme == "omnibotdiagnostics" && uri.host == "connect" &&
            uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null &&
            uri.rawPath.isNullOrEmpty())
        val fragment = requireNotNull(uri.rawFragment)
        require(fragment.matches(Regex("[A-Za-z0-9_-]+")))
        val payload = JsonParser.parseString(String(Base64.getUrlDecoder().decode(fragment), Charsets.UTF_8)).asJsonObject
        require(payload["schemaVersion"].asInt == 1)
        val endpoint = payload["endpoint"].asString
        RuntimeDiagnosticUploader.validateEndpoint(endpoint)
        val code = payload["code"].asString
        require(code.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        mapOf("endpoint" to endpoint, "code" to code)
    }.getOrNull()

    @Synchronized fun receive(value: String?): Boolean {
        val parsed = value?.let(::parse) ?: return false
        pending = parsed
        receivedAt = System.nanoTime()
        return true
    }

    @Synchronized fun consume(): Map<String, String>? {
        val result = pending
        pending = null
        return result?.takeIf { System.nanoTime() - receivedAt < 10L * 60 * 1_000_000_000 }
    }
}
