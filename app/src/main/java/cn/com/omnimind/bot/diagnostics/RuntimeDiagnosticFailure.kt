package cn.com.omnimind.bot.diagnostics

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Error-only projection. Never serializes request arguments or arbitrary RPC data. */
internal object RuntimeDiagnosticFailure {
    fun method(value: String): String =
        value.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$/.-]{0,95}")) } ?: "unknown"

    private val diagnosticFields = setOf(
        "message", "detail", "reason", "error", "cause", "code", "status", "type", "failureKind", "stack",
    )

    private fun data(value: JsonElement, depth: Int = 0): JsonElement? {
        if (depth > 4) return null
        return when (value) {
            is JsonObject -> JsonObject(value.entries.mapNotNull { (key, child) ->
                if (key !in diagnosticFields) null else data(child, depth + 1)?.let { key to it }
            }.toMap())
            is JsonArray -> JsonArray(value.take(8).mapNotNull { data(it, depth + 1) })
            is JsonPrimitive -> JsonPrimitive(RuntimeDiagnosticReport.redact(value.content).take(2_048))
        }
    }

    fun entry(owner: String, method: String, error: Throwable, context: String = ""): RuntimeLogEntry {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val rpc = causes.filterIsInstance<JsonRpcException>().firstOrNull()
        val type = if (rpc != null) "JsonRpcException" else error.javaClass.simpleName
        val detail = buildString {
            for (cause in causes) {
                appendLine("${cause.javaClass.name}: ${cause.message.orEmpty()}")
                if (cause is JsonRpcException) {
                    appendLine("rpcCode=${cause.code}")
                    cause.data?.let { appendLine("rpcDiagnosticData=${data(it)}") }
                }
                cause.stackTrace.take(24).forEach { appendLine("    at $it") }
            }
            if (context.isNotBlank()) appendLine(context)
        }
        return RuntimeLogEntry(
            level = "ERROR", tag = owner,
            message = "${method(method)} failed ($type${rpc?.let { "; rpcCode=${it.code}" }.orEmpty()})",
            stackTrace = RuntimeDiagnosticReport.redact(detail).take(8_192),
        )
    }
}
