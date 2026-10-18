package cn.com.omnimind.bot.diagnostics

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticReportTest {
    @Test fun removesCredentialsFromMessagesAndStacksBeforeExport() {
        val secrets = listOf("private-key-value-1234", "secret-refresh-value", "secret-id-value", "user@example.com")
        val raw = """HTTP 401 {"api_key":"${secrets[0]}", "refresh_token": "${secrets[1]}", "id_token":"${secrets[2]}"} ${secrets[3]}
            Authorization: Bearer fake-bearer-secret
            Cookie: login=private-cookie
            https://someone:password@example.com/api?token=private-query#private-fragment
        """.trimIndent()
        val json = RuntimeDiagnosticReport.encode("fixture", emptyMap(), listOf(RuntimeLogEntry(message = raw, stackTrace = raw)), 1)
        (secrets + listOf("fake-bearer-secret", "private-cookie", "private-query", "private-fragment", "someone:password")).forEach {
            assertFalse("leaked $it", json.contains(it))
        }
        assertTrue(json.contains("HTTP 401"))
        assertTrue(json.contains("https://example.com/api"))
        assertEquals(1, JsonParser.parseString(json).asJsonObject["schemaVersion"].asInt)
    }

    @Test fun removesStandaloneKeysJwtAndPrivateKeyBlocks() {
        val jwt = "eyJabcdefghijklmno.abcdefghijklmnop.abcdefghijklmnop"
        val key = "sk-proj-abcdefghijklmnop123456"
        val text = "failure $jwt $key\n-----BEGIN PRIVATE KEY-----\nprivate-data\n-----END PRIVATE KEY-----"
        val redacted = RuntimeDiagnosticReport.redact(text)
        listOf(jwt, key, "private-data").forEach { assertFalse(redacted.contains(it)) }
    }

    @Test fun limitsSerializedPayloadAndIncludesOmissionCount() {
        val logs = List(200) { RuntimeLogEntry(message = "测".repeat(5000), stackTrace = "堆".repeat(10000)) }
        val json = RuntimeDiagnosticReport.encode("fixture", emptyMap(), logs)
        assertTrue(json.toByteArray().size <= 1_048_576)
        val result = JsonParser.parseString(json).asJsonObject
        assertEquals(200, result["logs"].asJsonArray.size() + result["omittedLogCount"].asInt)
    }

    @Test fun receiverMustUseHttpsWithoutEmbeddedCredentialsOrQuery() {
        assertEquals("example.com", RuntimeDiagnosticUploader.validateEndpoint("https://example.com/v1/runtime-logs").host)
        listOf("http://example.com/", "https://user:pass@example.com/", "https://example.com/?token=value", "https://example.com/#key").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RuntimeDiagnosticUploader.validateEndpoint(value) }
        }
    }
}
