package cn.com.omnimind.bot.diagnostics

import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticFailureTest {
    @Test fun capturesRpcCodeCauseAndAllowedDataWithoutRequestPayloads() {
        val rpc = JsonRpcException(-32603, "Internal error", Json.parseToJsonElement("""{
            "message":"failed to reload config", "error":{"code":"fixture_error","reason":"TLS failed"},
            "params":{"prompt":"private user prompt"}, "config":"private config", "token":"private token"
        }"""))
        val entry = RuntimeDiagnosticFailure.entry("fixture", "model/list", IllegalStateException("outer", rpc))
        assertTrue(entry.message.contains("model/list"))
        assertTrue(entry.message.contains("rpcCode=-32603"))
        val detail = entry.stackTrace!!
        listOf("Internal error", "failed to reload config", "fixture_error", "TLS failed", "outer").forEach {
            assertTrue("missing $it", detail.contains(it))
        }
        listOf("private user prompt", "private config", "private token").forEach { assertFalse(detail.contains(it)) }
    }

    @Test fun redactsExceptionMessagesStacksAndProcessContextBeforeStorage() {
        val entry = RuntimeDiagnosticFailure.entry("fixture", "session/load", IllegalStateException(
            "request failed access_token=fixture-access password=fixture-pass https://someone:fixture-url@example.com/path?key=fixture-query"
        ), "Adapter stderr: refresh_token=fixture-refresh user@example.com")
        val stored = entry.message + entry.stackTrace
        listOf("fixture-access", "fixture-pass", "fixture-url", "fixture-query", "fixture-refresh", "user@example.com").forEach {
            assertFalse("leaked $it", stored.contains(it))
        }
        assertTrue(stored.contains("request failed"))
        assertTrue(stored.contains("Adapter stderr"))
    }

    @Test fun preservesRpcMethodsAndBoundsErrorSize() {
        listOf("model/list", "session/load", "agent/config/read", "$/cancel_request", "initialize").forEach {
            assertEquals(it, RuntimeDiagnosticFailure.method(it))
        }
        assertEquals("unknown", RuntimeDiagnosticFailure.method("model/list\nprivate"))
        val entry = RuntimeDiagnosticFailure.entry("fixture", "model/list", IllegalStateException("测".repeat(20_000)))
        assertTrue(entry.stackTrace!!.length <= 8_192)
    }
}
