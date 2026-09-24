package com.neuron.ai.di

import android.content.Context
import com.neuron.ai.core.agent.ToolExecutor
import com.neuron.ai.core.agent.ToolRegistry
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.coroutines.DefaultDispatcherProvider
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.BrowserManager
import com.neuron.ai.core.log.AndroidLogger
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.memory.MemoryStore
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.security.SecureCredentialStore
import com.neuron.ai.core.security.SecureCredentialStoreFactory
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.settings.SettingsRepositoryImpl
import com.neuron.ai.core.task.TaskManager
import com.neuron.ai.core.web.PageFetcher
import com.neuron.ai.core.web.SearchProvider
import com.neuron.ai.data.agent.DefaultAgentRuntime
import com.neuron.ai.data.agent.DefaultToolExecutor
import com.neuron.ai.data.attachment.AttachmentStore
import com.neuron.ai.data.browser.AndroidBrowserManager
import com.neuron.ai.data.conversation.RoomConversationRepository
import com.neuron.ai.data.db.NeuronDatabase
import com.neuron.ai.data.db.RoomTaskRecordStore
import com.neuron.ai.data.memory.MemoryManager
import com.neuron.ai.data.memory.RoomMemoryStore
import com.neuron.ai.data.permissions.SessionPermissionManager
import com.neuron.ai.data.provider.ProviderRepository
import com.neuron.ai.data.task.DefaultTaskManager
import com.neuron.ai.data.terminal.TerminalManager
import com.neuron.ai.data.tool.InMemoryToolRegistry
import com.neuron.ai.data.tool.SafeTools
import com.neuron.ai.data.web.DuckDuckGoSearchProvider
import com.neuron.ai.data.web.HttpPageFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Hand-rolled dependency container: one explicit place where every
 * collaborator is created. Milestone 3 adds web search, the browser manager,
 * memory and the context engine — all wired through abstractions.
 */
class AppContainer(context: Context) {

    val logger: Logger = AndroidLogger()

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    val secureCredentials: SecureCredentialStore =
        SecureCredentialStoreFactory.create(context, logger)

    val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl(context, dispatchers)

    val json: Json = Json { ignoreUnknownKeys = true }

    val database: NeuronDatabase = NeuronDatabase.get(context)

    val conversationRepository: ConversationRepository =
        RoomConversationRepository(database.conversationDao(), dispatchers, json)

    val attachmentStore: AttachmentStore =
        AttachmentStore(context, dispatchers, logger)

    val providerRepository: ProviderRepository =
        ProviderRepository(context, secureCredentials, dispatchers, logger).apply {
            imageLoader = { attachmentId -> attachmentStore.readBytesById(attachmentId) }
        }

    val permissionManager: PermissionManager = SessionPermissionManager()

    val toolRegistry: ToolRegistry = InMemoryToolRegistry()

    val toolExecutor: ToolExecutor =
        DefaultToolExecutor(toolRegistry, permissionManager, logger)

    val agentRuntime: DefaultAgentRuntime = DefaultAgentRuntime(dispatchers)

    val workspaceManager: com.neuron.ai.data.workspace.WorkspaceManagerImpl =
        com.neuron.ai.data.workspace.WorkspaceManagerImpl(context.filesDir, dispatchers)

    val terminalManager: TerminalManager = TerminalManager(dispatchers)

    // ---- Milestone 3 services --------------------------------------------------------------

    /** Real web search over the user's own network — no paid API, no backend. */
    val searchProvider: SearchProvider = DuckDuckGoSearchProvider(dispatchers)

    /** Bounded page fetcher/extractor used by web tools. */
    val pageFetcher: PageFetcher = HttpPageFetcher(dispatchers)

    /** Real WebView-backed browser sessions, keyed per conversation. */
    val browserManager: BrowserManager = AndroidBrowserManager(context, dispatchers)

    /** Secret-filtered, scope-isolated memory. */
    val memoryStore: MemoryStore = RoomMemoryStore(context, database, dispatchers)

    /** Conversation context scope for the memory tools, resolved per chat. */
    var memoryWorkspaceScopeProvider: suspend () -> String? = { null }

    val memoryManager: MemoryManager = MemoryManager(memoryStore)

    val memoryTools: List<com.neuron.ai.core.agent.Tool> =
        MemoryManager.Tools(memoryManager) { memoryWorkspaceScopeProvider() }.all

    val taskManager: DefaultTaskManager = DefaultTaskManager(
        dispatchers,
        RoomTaskRecordStore(database.taskDao())
    )

    private val initScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    init {
        initScope.launch {
            taskManager.restore()
            toolRegistry.register(SafeTools.CurrentTime())
            toolRegistry.register(SafeTools.Calculator())
            toolRegistry.register(SafeTools.TextStats())
            val workspace = java.io.File(context.filesDir, "workspace")
            toolRegistry.register(SafeTools.FileRead(workspace))
            toolRegistry.register(SafeTools.FileSearch(workspace))
            // Milestone 3: web search + reading + memory are global tools.
            toolRegistry.register(
                com.neuron.ai.data.tool.WebTools.WebSearch(searchProvider, pageFetcher)
            )
            toolRegistry.register(com.neuron.ai.data.tool.WebTools.WebRead(pageFetcher))
            memoryTools.forEach { toolRegistry.register(it) }
        }
    }
}
