package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderReadinessTest {
    @Test
    fun `seeded preset endpoints are not configured without credentials`() {
        OfficialProviderRegistry.officialProfiles().forEach { profile ->
            assertFalse(profile.name, profile.copy(apiKey = "", customHeaders = emptyMap()).isConfigured())
            assertFalse(
                profile.name,
                ModelProviderConfig(baseUrl = profile.baseUrl, providerType = profile.sourceType).isConfigured(),
            )
        }
    }

    @Test
    fun `preset credentials make both profile and editor config ready`() {
        OfficialProviderRegistry.officialProfiles().forEach { profile ->
            assertTrue(profile.copy(apiKey = "test-credential").isConfigured())
            assertTrue(
                ModelProviderConfig(
                    baseUrl = profile.baseUrl,
                    providerType = profile.sourceType,
                    apiKey = "test-credential",
                ).isConfigured(),
            )
        }
    }

    @Test
    fun `custom authentication headers work without an API key`() {
        val profile = OfficialProviderRegistry.officialProfiles().first().copy(
            apiKey = "",
            customHeaders = mapOf("X-Api-Key" to "test-credential"),
        )
        assertTrue(profile.isConfigured())
        assertFalse(profile.copy(customHeaders = mapOf("X-Api-Key" to "  ")).isConfigured())
        assertFalse(profile.copy(customHeaders = mapOf("Accept" to "application/json")).isConfigured())
        assertFalse(profile.copy(customHeaders = mapOf("X-Api-Key" to "invalid\nheader")).isConfigured())
    }

    @Test
    fun `anonymous local providers remain supported`() {
        val profile = ModelProviderProfile(
            id = "local",
            name = "Local API",
            baseUrl = "http://127.0.0.1:8080/v1",
        )
        assertTrue(profile.isConfigured())
        assertTrue(ModelProviderConfig(baseUrl = profile.baseUrl).isConfigured())
        assertFalse(profile.copy(baseUrl = "").isConfigured())
    }
}
