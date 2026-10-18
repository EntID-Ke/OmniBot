package cn.com.omnimind.bot.diagnostics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RuntimeDiagnosticTransportTest {
    @Test fun authenticatedPostDoesNotFollowRedirectOrSendCodeInUrl() {
        MockWebServer().use { receiver ->
            MockWebServer().use { other ->
                other.start()
                receiver.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/elsewhere")))
                receiver.start()
                RuntimeDiagnosticUploader.createUploadCall(receiver.url("/v1/runtime-logs"), "fixture-code", "{}").execute().use {
                    assertEquals(302, it.code)
                }
                val request = receiver.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("/v1/runtime-logs", request.path)
                assertEquals("Bearer fixture-code", request.getHeader("Authorization"))
                assertEquals("{}", request.body.readUtf8())
                assertNull(other.takeRequest(150, TimeUnit.MILLISECONDS))
            }
        }
    }
}
