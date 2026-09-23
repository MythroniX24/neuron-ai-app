package com.neuron.ai.di

import android.content.Context
import com.neuron.ai.core.agent.ToolExecutor
import com.neuron.ai.core.agent.ToolRegistry
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.coroutines.DefaultDispatcherProvider
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.AndroidLogger
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.security.SecureCredentialStore
import com.neuron.ai.core.security.SecureCredentialStoreFactory
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.settings.SettingsRepositoryImpl
import com.neuron.ai.core.task.TaskManager
import com.neuron.ai.data.agent.DefaultAgentRuntime
import com.neuron.ai.data.agent.DefaultToolExecutor
import com.neuron.ai.data.attachment.AttachmentStore
import com.neuron.ai.data.conversation.RoomConversationRepository
import com.neuron.ai.data.db.NeuronDatabase
import com.neuron.ai.data.db.RoomTaskRecordStore
import com.neuron.ai.data.permissions.SessionPermissionManager
import com.neuron.ai.data.provider.ProviderRepository
import com.neuron.ai.data.task.DefaultTaskManager
import com.neuron.ai.data.tool.InMemoryToolRegistry
import com.neuron.ai.data.tool.SafeTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Hand-rolled dependency container: one explicit place where every
 * collaborator is created. Milestone 1 adds task persistence, the agent
 * runtime and a risk-aware tool executor.
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
        ProviderRepository(context, secureCredentials, dispatchers, logger)

    val permissionManager: PermissionManager = SessionPermissionManager()

    val toolRegistry: ToolRegistry = InMemoryToolRegistry()

    val toolExecutor: ToolExecutor =
        DefaultToolExecutor(toolRegistry, permissionManager, logger)

    val agentRuntime: DefaultAgentRuntime = DefaultAgentRuntime(dispatchers)

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
        }
    }
}
