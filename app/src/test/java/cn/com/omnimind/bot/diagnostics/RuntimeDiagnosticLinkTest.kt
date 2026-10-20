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

    @Test fun tcpLinkRequiresReceiverPinAndExplicitPort() {
        val endpoint = "tls://192.0.2.10:50919/" + "a".repeat(64)
        assertEquals(endpoint, RuntimeDiagnosticLink.parse(link(endpoint))?.get("endpoint"))
        for (invalid in listOf("tls://192.0.2.10:50919", "tcp://192.0.2.10:50919", "tls://192.0.2.10/" + "a".repeat(64),
            endpoint + "?secret=bad", endpoint.replace("192.0.2.10", "user@192.0.2.10"))) {
            assertNull(RuntimeDiagnosticLink.parse(link(invalid)))
        }
    }
}
