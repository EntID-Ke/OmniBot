package cn.com.omnimind.bot.diagnostics

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticLinkTest {
    private fun link(endpoint: String = "https://receiver.example/v1/runtime-logs", code: String = "a".repeat(43)): String {
        val payload = """{"schemaVersion":1,"endpoint":"$endpoint","code":"$code"}"""
        return "omnibotdiagnostics://connect#" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    }

    @Test fun connectionIsValidatedAndConsumedOnlyOnceWithoutStartingCollection() {
        assertTrue(RuntimeDiagnosticLink.receive(link()))
        assertEquals("https://receiver.example/v1/runtime-logs", RuntimeDiagnosticLink.consume()?.get("endpoint"))
        assertNull(RuntimeDiagnosticLink.consume())
        assertEquals(false, RuntimeDiagnosticUploader.state()["active"])
    }

    @Test fun malformedOrCredentialBearingEndpointsCannotBeImported() {
        for (endpoint in listOf("http://receiver.example", "https://user:pass@receiver.example", "https://receiver.example?token=unsafe", "https://receiver.example#secret")) {
            assertNull(RuntimeDiagnosticLink.parse(link(endpoint)))
        }
        assertNull(RuntimeDiagnosticLink.parse(link(code = "short")))
        assertNull(RuntimeDiagnosticLink.parse(link().replace("//connect#", "//connect?extra=1#")))
        assertNull(RuntimeDiagnosticLink.parse(link().replace("//connect#", "//another#")))
        assertNull(RuntimeDiagnosticLink.parse("omnibotdiagnostics://connect#not-json"))
    }
}
