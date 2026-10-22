package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CodexSubscriptionConfigurationTest {
    private fun input(provider: AgentProviderCredentials? = null) = AgentProviderMappingInput(
        agentId = "codex-acp", provider = provider,
        model = "official-fixture-model", harnessAdapter = AcpHarnessAdapters.codex,
        codexAuthMode = CodexAuthMode.CHATGPT,
    )

    @Test fun subscriptionNeverWritesCredentialsOrCustomProviderCatalog() {
        val source = input(AgentProviderCredentials("https://api.example/v1", "fixture-api-key"))
        val mapping = AgentConfigAdapterRegistry.map(source)
        val writes = AgentConfigAdapterRegistry.launchConfigWrites(source, mapping,
            listOf(ProviderModelOption(id = "api-only-model")), "")
        assertEquals(CODEX_SUBSCRIPTION_HOME, mapping.environment["CODEX_HOME"])
        assertFalse(mapping.environment.containsKey("OPENAI_API_KEY"))
        assertNull(mapping.codexBaseUrl)
        assertNull(mapping.codexModel)
        assertEquals(listOf(CODEX_SUBSCRIPTION_CONFIG), writes.map { it.path })
        assertFalse(writes.single().content.contains("fixture-api-key"))
        assertFalse(writes.single().content.contains("api-only-model"))
        assertFalse(writes.single().content.contains("model_provider"))
        assertTrue(writes.single().content.contains("forced_login_method = \"chatgpt\""))
    }

    @Test fun subscriptionWorksWithoutSharedApiCredentials() {
        val mapping = AgentConfigAdapterRegistry.map(input())
        assertEquals(CODEX_SUBSCRIPTION_HOME, mapping.environment["CODEX_HOME"])
    }

    @Test fun subscriptionSettingsDoNotReadOrExposeTheOAuthFile() = runBlocking {
        val result = CodexConfigAdapter.readConfig(input(), object : AgentConfigFileAccess {
            override suspend fun read(path: String, executorKey: String): String =
                error("Subscription settings must not read credentials")
            override suspend fun write(path: String, content: String, executorKey: String) =
                error("Reading settings must not write credentials")
        })
        assertEquals("", result["apiKey"])
        assertEquals("chatgpt", result["authMode"])
    }

    @Test fun loginShellCredentialsAreExplicitlyRemoved() {
        val mapping = AgentConfigAdapterRegistry.map(input())
        val keys = AcpHarnessAdapters.codex.environmentKeysToUnset(mapping.environment)
        assertTrue(keys.containsAll(listOf("OPENAI_API_KEY", "CODEX_API_KEY", "CODEX_ACCESS_TOKEN", "OPENAI_BASE_URL")))
        assertEquals(mapOf("CODEX_HOME" to CODEX_SUBSCRIPTION_HOME, "NO_BROWSER" to "1", "CODEX_PATH" to CODEX_MANAGED_COMMAND),
            AcpHarnessAdapters.codex.environmentOverridesAfterShell(mapping.environment))
        assertTrue(AcpHarnessAdapters.claudeCode.environmentKeysToUnset(mapping.environment).isEmpty())
    }

    @Test fun bothModesUseTheManagedCliInsteadOfTheAdaptersOlderDependency() {
        for (mode in CodexAuthMode.entries) {
            val mapping = AgentConfigAdapterRegistry.map(input().copy(codexAuthMode = mode))
            assertEquals(CODEX_MANAGED_COMMAND, mapping.environment["CODEX_PATH"])
            val afterShell = AcpHarnessAdapters.codex.environmentOverridesAfterShell(
                mapping.environment + ("CODEX_PATH" to "/old/transitive/codex"))
            assertEquals(CODEX_MANAGED_COMMAND, afterShell["CODEX_PATH"])
        }
    }

    @Test fun switchingBackPreservesTheOriginalApiHomeAndProvider() {
        val source = input(AgentProviderCredentials("https://api.example/v1", "fixture-api-key"))
            .copy(codexAuthMode = CodexAuthMode.API)
        val mapping = AgentConfigAdapterRegistry.map(source)
        val writes = AgentConfigAdapterRegistry.launchConfigWrites(source, mapping, emptyList(), "")
        assertEquals("/root/.codex", mapping.environment["CODEX_HOME"])
        assertEquals("fixture-api-key", mapping.environment["OPENAI_API_KEY"])
        assertTrue(writes.any { it.path == CODEX_AUTH_JSON_PATH })
        assertFalse(writes.any { it.path.startsWith(CODEX_SUBSCRIPTION_HOME) })
    }

    @Test fun unknownModeFailsInsteadOfSilentlyChangingBillingSource() {
        assertEquals(CodexAuthMode.API, CodexAuthMode.parse(null))
        assertThrows(IllegalArgumentException::class.java) { CodexAuthMode.parse("unknown") }
    }

    @Test fun authenticationCannotWriteTheOtherModesCredentialHome() {
        val adapter = AcpHarnessAdapters.codex
        val subscription = mapOf("CODEX_HOME" to CODEX_SUBSCRIPTION_HOME)
        adapter.validateAuthenticationMethod("chat-gpt-device-code", subscription)
        assertThrows(IllegalArgumentException::class.java) {
            adapter.validateAuthenticationMethod("api-key", subscription)
        }
        assertThrows(IllegalArgumentException::class.java) {
            adapter.validateAuthenticationMethod("chat-gpt-device-code", mapOf("CODEX_HOME" to "/root/.codex"))
        }
    }
}
