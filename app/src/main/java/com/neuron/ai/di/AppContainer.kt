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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Hand-rolled dependency container: one explicit place where every
 * collaborator is created. Milestone 3 adds web search, the browser manager,
 * memory and the context engine — all wired through abstractions.
 */
class AppContainer(context: Context) {

    /** App context exposed for ViewModels that need system services (Local AI import picker). */
    val appContext: Context = context.applicationContext

    init {
        // Milestone 5: ggml discovers runtime backends (per-ISA CPU variants,
        // Vulkan GPU) by dlopen'ing the .so files shipped in the app's native
        // library directory. The actual dlopen work happens lazily on the
        // inference thread (inside LocalEngineLoader.load), not here.
        com.neuron.ai.data.local.LocalEngineLoader.setBackendDir(
            context.applicationInfo.nativeLibraryDir
        )
    }

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

    // ---- Local AI (on-device inference) -------------------------------------

    /**
     * Milestone 7: thermal/battery watcher. Started with the container so the
     * throttle policy always sees live device health; the pure policy (not
     * this class) decides what to do about it.
     */
    val deviceHealthMonitor: com.neuron.ai.data.local.DeviceHealthMonitor by lazy {
        com.neuron.ai.data.local.DeviceHealthMonitor(context, logger).also { it.start() }
    }

    /** GGUF model registry: imports, ticks, single-active load state. */
    val localModelRepository: com.neuron.ai.data.local.LocalModelRepository by lazy {
        com.neuron.ai.data.local.LocalModelRepository(
            context, dispatchers, logger, deviceHealthMonitor
        )
    }

    /** The local model as JUST ANOTHER AiProvider for the whole stack. */
    val localAiProvider: com.neuron.ai.data.local.LocalAiProvider by lazy {
        com.neuron.ai.data.local.LocalAiProvider(
            localModelRepository,
            // Milestone 9: the vision projector needs the raw image bytes.
            readImageBytes = { attachmentId -> attachmentStore.readBytesById(attachmentId) }
        )
    }

    /** Hugging Face Hub search (free API, no key). */
    val hfHubClient: com.neuron.ai.data.local.HfHubClient by lazy {
        com.neuron.ai.data.local.HfHubClient()
    }

    /** Background GGUF downloads with pause/resume/checksum/restart survival. */
    val downloadManager: com.neuron.ai.data.local.ModelDownloadManager by lazy {
        com.neuron.ai.data.local.ModelDownloadManager(
            context, localModelRepository, dispatchers, logger, hfHubClient
        ).also { it.restore() }
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

    /**
     * Orchestrated search (fast/reliable web search architecture): parallel
     * fan-out across registered adapters behind the stable [SearchProvider]
     * interface, per-provider circuit breakers, TTL cache, dedup + rerank.
     * Add future adapters (incl. self-hosted) to the registry list only.
     */
    val searchOrchestrator: com.neuron.ai.data.web.SearchOrchestrator =
        com.neuron.ai.data.web.SearchOrchestrator(
            registry = com.neuron.ai.data.web.SearchProviderRegistry(
                providers = listOf(
                    searchProvider, // DuckDuckGo HTML — keyless scrape
                    com.neuron.ai.data.web.BingHtmlSearchProvider(dispatchers), // keyless scrape
                    com.neuron.ai.data.web.MojeekSearchProvider(dispatchers) // keyless, independent index
                ),
                logger = logger
            ),
            cache = com.neuron.ai.data.web.SearchCache(),
            logger = logger
        )

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
        MemoryManager.Tools(memoryManager, permissionManager) {
            memoryWorkspaceScopeProvider()
        }.all

    val taskManager: DefaultTaskManager = DefaultTaskManager(
        dispatchers,
        RoomTaskRecordStore(database.taskDao())
    )

    /**
     * Phase-3 context orchestration (CONTEXT_ARCHITECTURE.md §5). Runs in
     * SHADOW MODE alongside the legacy ChatContextEngine (§14.3) — the
     * ChatViewModel logs both side by side but still feeds the model the
     * legacy context until the comparison is verified.
     */
    val contextOrchestrator: com.neuron.ai.context.orchestrator.ContextOrchestrator by lazy {
        com.neuron.ai.context.orchestrator.ContextOrchestrator(
            memoryProvider = com.neuron.ai.context.providers.MemoryContextProvider { type, scopeId, limit ->
                memoryManager.relevant(type, scopeId, limit)
            },
            taskProvider = com.neuron.ai.context.providers.TaskContextProvider {
                taskManager.tasks.first()
            },
            messagesProvider = { conversationId ->
                conversationRepository.messagesOf(conversationId).first()
            }
        )
    }

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
                com.neuron.ai.data.tool.WebTools.WebSearch(searchOrchestrator, pageFetcher)
            )
            toolRegistry.register(com.neuron.ai.data.tool.WebTools.WebRead(pageFetcher))
            memoryTools.forEach { toolRegistry.register(it) }
        }
    }
}
