package cn.com.omnimind.bot.plugin

import android.content.Context
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.plugin.official.OmniVlmLiteProvider
import cn.com.omnimind.bot.plugin.official.OfficialOmniPluginProviders
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class OmniPluginHost private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmupJob: Job? = null
    private val platform = OmniPluginPlatform(
        providerSource = {
            OmniPluginProviderRegistry.createProviders(applicationContext)
        },
        stateStore = SharedPreferencesOmniPluginStateStore(applicationContext),
        reservedToolNames =
            AgentToolDefinitions.reservedToolNames() + PluginDiscoveryToolHandler.TOOL_NAMES,
        defaultEnabledPluginIds = setOf(OmniVlmLiteProvider.ID),
    )

    suspend fun list(): List<OmniPluginState> {
        val catalog = withContext(Dispatchers.IO) { platform.catalog() }
        warmup()
        return catalog
    }

    @Synchronized
    private fun warmup() {
        if (warmupJob?.isActive == true) return
        warmupJob = scope.launch {
            try {
                OfficialOmniPluginProviders.refreshCatalog(applicationContext)
                platform.list()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                OmniLog.w("[OmniPluginHost]", "Plugin preparation failed: ${error.message}")
            }
        }
    }

    suspend fun install(pluginId: String): OmniPluginState {
        OfficialOmniPluginProviders.refreshCatalog(applicationContext, force = true)
        return platform.install(pluginId)
    }

    suspend fun update(pluginId: String): OmniPluginState {
        OfficialOmniPluginProviders.refreshCatalog(applicationContext, force = true)
        return platform.update(pluginId)
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): OmniPluginState {
        return platform.setEnabled(pluginId, enabled)
    }

    suspend fun uninstall(pluginId: String) = platform.uninstall(pluginId)

    suspend fun openSession(): OmniPluginSession {
        val pluginSession = platform.openSession()
        val discoveryHandler = PluginDiscoveryToolHandler(applicationContext, ::list)
        return OmniPluginSession(
            toolDefinitions =
                PluginDiscoveryToolHandler.definitions() + pluginSession.toolDefinitions,
            toolHandlers = listOf(discoveryHandler) + pluginSession.toolHandlers,
        )
    }

    suspend fun listActions(): List<OmniPluginActionDefinition> {
        return platform.listActionDefinitions()
    }

    suspend fun invokeAction(
        pluginId: String,
        actionId: String,
        args: JsonObject,
    ): JsonObject {
        val session = platform.openActionSession(pluginId)
        return try {
            val definition = session.actionDefinitions.singleOrNull { definition ->
                definition.ownerPluginId == pluginId && definition.id == actionId
            } ?: throw IllegalArgumentException(
                "Unknown enabled plugin action: $pluginId/$actionId"
            )
            val handler = session.actionHandlers.singleOrNull { it.canHandle(definition.id) }
                ?: error("Plugin action has no handler: $pluginId/$actionId")
            handler.execute(definition.id, args)
        } finally {
            session.closeSuspending()
        }
    }

    companion object {
        @Volatile
        private var instance: OmniPluginHost? = null

        fun get(context: Context): OmniPluginHost {
            return instance ?: synchronized(this) {
                instance ?: OmniPluginHost(context).also { instance = it }
            }
        }
    }
}
