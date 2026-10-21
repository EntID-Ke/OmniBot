package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.mcp.McpServerManager
import com.tencent.mmkv.MMKV
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class LocalAcpSessionRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun modelRefreshRecoversMissingRolloutInItsOriginalConversation() = runBlocking {
        withRuntime(bound = true) {
            val result = runtime.handleMethod("model/list", mapOf(
                "refreshConfig" to true, "conversationId" to 99L,
            )) as Map<*, *>
            assertEquals("fresh-model", ((result["models"] as List<*>).single() as Map<*, *>)["id"])
            assertEquals(listOf("load:old-session", "new"), calls)
            assertEquals(listOf("fresh-session" to 7L), bindings)
            assertFalse(sessions.containsKey("old-session"))
            assertSame(fresh, sessions["fresh-session"])
            assertEquals(7L, (runtime.field("pendingHandoffConversationIds") as Map<*, *>)["fresh-session"])
            val reopened = runtime.handleMethod("session/load", mapOf(
                "sessionId" to "old-session", "conversationId" to 7L,
            )) as Map<*, *>
            assertEquals("fresh-session", reopened["sessionId"])
            assertEquals(7L, reopened["conversationId"])
            // A later catalog read must select the replacement and never
            // resurrect the stale cached session or send a prompt.
            runtime.handleMethod("model/list", emptyMap())
            assertEquals(listOf("load:old-session", "new"), calls)
        }
    }

    @Test fun unboundCatalogRecoveryDoesNotCreateAConversation() = runBlocking {
        withRuntime(bound = false) {
            runtime.handleMethod("model/list", mapOf("refreshConfig" to true, "conversationId" to 99L))
            assertTrue(bindings.isEmpty())
            assertEquals("fresh-session", runtime.field("catalogSessionId"))
            runtime.handleMethod("model/list", emptyMap())
            assertEquals(listOf("load:old-session", "new"), calls)
        }
    }

    @Test fun persistedResumeUsesTheSameRecoveryOwnerAndPreservesConversation() = runBlocking {
        withRuntime(bound = true) {
            sessions.clear()
            val info = runtime.field("agentInfo") as AgentInfo
            `when`(info.capabilities).thenReturn(AgentCapabilities(loadSession = true,
                sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities())))
            val result = runtime.handleMethod("session/resume", mapOf("sessionId" to "old-session")) as Map<*, *>
            assertEquals("fresh-session", result["sessionId"])
            assertEquals("old-session", result["previousSessionId"])
            assertEquals(false, result["sessionRestored"])
            assertEquals(7L, result["conversationId"])
            assertEquals(listOf("resume:old-session", "new"), calls)
            assertEquals(listOf("fresh-session" to 7L), bindings)
        }
    }

    @Test fun unrelatedFailuresAndFailedReplacementPreserveTheExistingSession() = runBlocking {
        for (message in listOf("Internal error", "provider authentication failed",
                "timeout waiting for child process to exit")) {
            withRuntime(bound = true) {
                val failure = IllegalStateException(message)
                loadFailure = failure
                try {
                    runtime.handleMethod("model/list", mapOf("refreshConfig" to true))
                    fail("The original error must reach the caller")
                } catch (error: IllegalStateException) { assertSame(failure, error) }
                assertEquals(listOf("load:old-session"), calls)
                assertTrue(sessions.containsKey("old-session"))
                assertTrue(bindings.isEmpty())
            }
        }
        withRuntime(bound = true) {
            val failure = IllegalStateException("new session rejected")
            newFailure = failure
            try {
                runtime.handleMethod("model/list", mapOf("refreshConfig" to true))
                fail("Replacement failure must reach the caller")
            } catch (error: IllegalStateException) { assertSame(failure, error) }
            assertTrue(sessions.containsKey("old-session"))
            assertFalse(sessions.containsKey("fresh-session"))
            assertTrue(bindings.isEmpty())
        }
    }

    @Test fun activeTurnPreventsRefreshAndSuccessfulReloadKeepsItsSession() = runBlocking {
        withRuntime(bound = true) {
            ownership.reserve("old-session", "visible-turn", "request")
            try {
                runtime.handleMethod("model/list", mapOf("refreshConfig" to true))
                fail("Refresh must not replace an active prompt")
            } catch (error: IllegalStateException) {
                assertEquals("本轮结束后可刷新模型列表", error.message)
            }
            assertTrue(calls.isEmpty())
            assertEquals("visible-turn", ownership.activeTurnId("old-session"))
            ownership.finish("old-session", "visible-turn", "completed")
            loadFailure = null
            loaded = session("old-session", "reloaded-model")
            val result = runtime.handleMethod("model/list", mapOf("refreshConfig" to true)) as Map<*, *>
            assertEquals("reloaded-model", result["currentModelId"])
            assertEquals(listOf("load:old-session"), calls)
            assertTrue(bindings.isEmpty())
        }
    }

    private suspend fun withRuntime(bound: Boolean, block: suspend Fixture.() -> Unit) {
        // Replace only the native storage boundary in this isolated JVM.
        // No MCP server is started and no actual vault/account is read.
        val delegate = McpServerManager.javaClass.getDeclaredField("mmkv\$delegate")
            .apply { isAccessible = true }.get(McpServerManager)
        val storage = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val token = McpServerManager.javaClass.getDeclaredField("cachedToken").apply { isAccessible = true }
        val previousStorage = storage.get(delegate)
        val previousToken = token.get(McpServerManager)
        storage.set(delegate, mock(MMKV::class.java))
        token.set(McpServerManager, "fixture-mcp-token")
        try {
            val fixture = Fixture(bound)
            try { fixture.block() } finally { fixture.scope.cancel() }
        } finally {
            storage.set(delegate, previousStorage)
            token.set(McpServerManager, previousToken)
        }
    }

    private inner class Fixture(bound: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val calls = mutableListOf<String>()
        val bindings = mutableListOf<Pair<String, Long?>>()
        var binding = if (bound) AgentSessionBinding(7, "old-session", "/workspace", 1, 1) else null
        var loadFailure: Throwable? = IllegalStateException("no rollout found for thread id fixture-session")
        var newFailure: Throwable? = null
        val fresh = session("fresh-session", "fresh-model")
        var loaded = fresh
        val repository = mock(AgentSessionBindingRepository::class.java) { call ->
            when (call.method.name) {
                "getBindingByThreadId" -> binding?.takeIf { it.threadId == call.arguments[0] }
                "getBindingByConversationId" -> binding?.takeIf { it.conversationId == call.arguments[0] }
                "ensureBinding" -> {
                    val id = call.arguments[0] as String
                    val conversation = call.arguments[1] as Long?
                    bindings += id to conversation
                    binding = AgentSessionBinding(checkNotNull(conversation), id, "/workspace", 1, 1)
                    conversation
                }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val client = mock(Client::class.java) { call ->
            // Kotlin's inline SessionId is a String at the JVM boundary.
            val method = call.method.name.substringBefore('-')
            when (method) {
                "loadSession", "resumeSession" -> {
                    val operation = if (method == "loadSession") "load" else "resume"
                    calls += "$operation:${call.arguments[0] as String}"
                    loadFailure?.let { throw it }
                    loaded
                }
                "newSession" -> { calls += "new"; newFailure?.let { throw it }; fresh }
                else -> error("Unexpected ACP operation: ${call.method.name}")
            }
        }
        val context = acpProfileStoreTestContext(folder.newFolder())
        val runtime = LocalAcpRuntime(context, scope, repository, AcpAgentProfileStore(context),
            prepareLaunchEnvironment = { emptyMap() }, buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Recovery must not replay or synthesize turn events") },
            turnOwnership = ownership)
        @Suppress("UNCHECKED_CAST")
        val sessions = runtime.field("sessions") as ConcurrentHashMap<String, ClientSession>
        init {
            val info = mock(AgentInfo::class.java)
            `when`(info.capabilities).thenReturn(AgentCapabilities(loadSession = true))
            runtime.setField("agentInfo", info)
            runtime.setField("client", client)
            runtime.setField("activeProfile", AcpAgentProfile(
                id = "fixture-agent", name = "Fixture", command = "fixture-acp"))
            runtime.setField("sessionMcpEnabled", false)
            sessions["old-session"] = session("old-session", "stale-model")
            if (!bound) runtime.setField("catalogSessionId", "old-session")
        }
    }

    private fun session(id: String, model: String): ClientSession {
        val session = mock(ClientSession::class.java)
        `when`(session.sessionId).thenReturn(SessionId(id))
        `when`(session.parameters).thenReturn(SessionCreationParameters("/workspace", emptyList()))
        `when`(session.configOptionsSupported).thenReturn(true)
        `when`(session.configOptions).thenReturn(MutableStateFlow(listOf(SessionConfigOption.Select(
            id = SessionConfigId("model"), name = "Model", category = SessionConfigOptionCategory.MODEL,
            currentValue = SessionConfigValueId(model), options = SessionConfigSelectOptions.Flat(listOf(
                SessionConfigSelectOption(SessionConfigValueId(model), model),
            )),
        ))))
        return session
    }

    private fun LocalAcpRuntime.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }
    private fun LocalAcpRuntime.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
