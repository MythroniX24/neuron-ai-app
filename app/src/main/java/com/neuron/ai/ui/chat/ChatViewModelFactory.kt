package com.neuron.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.data.agent.ToolUsingAgent
import com.neuron.ai.di.AppContainer
import kotlinx.coroutines.flow.first

class ChatViewModelFactory(
    private val container: AppContainer,
    private val conversationId: String
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "Unknown ViewModel: $modelClass"
        }
        // Conversation-scoped tool environment; registers this chat's
        // workspace/terminal/coding tools into the shared registry (same ids
        // replace on chat switch — one active chat screen at a time).
        // Registration is suspend; the factory runs it blockingly because
        // ViewModel creation is synchronous by design.
        kotlinx.coroutines.runBlocking {
            val toolEnv = ConversationToolEnv(
                conversationId = conversationId,
                conversations = container.conversationRepository as com.neuron.ai.data.conversation.RoomConversationRepository,
                workspaces = container.workspaceManager,
                dispatchers = container.dispatchers
            )
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.ListDir(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.ReadFile(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.WriteFile(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.MakeDir(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.Rename(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.Move(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.Copy(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.Delete(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.Search(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.WorkspaceTools.Metadata(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.CodingTools.EditCode(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.CodingTools.ApplyPatch(toolEnv))
            container.toolRegistry.register(com.neuron.ai.data.tool.CodingTools.Diff(toolEnv))
            container.toolRegistry.register(
                com.neuron.ai.data.tool.CodingTools.RunBuild(toolEnv, container.terminalManager)
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.CodingTools.RunTests(toolEnv, container.terminalManager)
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.TerminalTool(toolEnv, container.terminalManager)
            )
        }

        return ChatViewModel(
            conversationId = conversationId,
            conversations = container.conversationRepository,
            providers = container.providerRepository,
            tasks = container.taskManager,
            dispatchers = container.dispatchers,
            logger = container.logger,
            agentFactory = { provider, model, toolIds ->
                ToolUsingAgent(
                    provider = provider,
                    model = model,
                    toolRegistry = container.toolRegistry,
                    toolExecutor = container.toolExecutor,
                    logger = container.logger
                )
            },
            defaultModelId = container.providerRepository.defaultModelId.value,
            toolIdsProvider = { container.toolRegistry.tools.first().map { it.id }.toSet() },
            importAttachmentFn = { uri -> container.attachmentStore.importFromUri(uri) },
            importCaptureFn = { file -> container.attachmentStore.importCapture(file) },
            workspaces = container.workspaceManager,
            terminalManager = container.terminalManager
        ) as T
    }
}
