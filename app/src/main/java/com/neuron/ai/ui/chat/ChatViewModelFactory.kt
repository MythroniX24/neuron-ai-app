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
        // Milestone 3: the context engine injects relevant, scope-filtered
        // memory (global preferences + this conversation's project memory)
        // into each turn — never the whole store.
        val contextEngine = com.neuron.ai.ui.chat.ChatContextEngine(
            memoryBlockProvider = {
                val manager = container.memoryManager
                val workspaceId = container.memoryWorkspaceScopeProvider()
                val prefs = manager.relevant(
                    com.neuron.ai.core.memory.MemoryType.USER_PREFERENCE,
                    com.neuron.ai.data.memory.SCOPE_GLOBAL,
                    10
                )
                val project = workspaceId?.let { wsId ->
                    manager.relevant(com.neuron.ai.core.memory.MemoryType.PROJECT, wsId, 10)
                }.orEmpty()
                (prefs + project).take(16).ifEmpty { null }?.joinToString("\n") { entry ->
                    "- ${entry.key}: ${entry.value}"
                }
            }
        )
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
                com.neuron.ai.data.tool.CodingTools.RunBuild(
                    toolEnv, container.terminalManager, container.permissionManager
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.CodingTools.RunTests(
                    toolEnv, container.terminalManager, container.permissionManager
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.TerminalTool(
                    toolEnv, container.terminalManager, container.permissionManager
                )
            )
            // Milestone 3: browser tools — per-conversation toggle + permission
            // gates; side-effect tools (click/type/select) always ask.
            val browserContextKey = conversationId
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.OpenUrl(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.ReadPage(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.FindOnPage(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.GetLinks(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.ClickElement(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.TypeText(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            container.toolRegistry.register(
                com.neuron.ai.data.tool.BrowserTools.SelectOption(
                    toolEnv, container.permissionManager, container.browserManager, browserContextKey
                )
            )
            // Memory tools resolve THIS conversation's workspace scope at run time.
            container.memoryWorkspaceScopeProvider = {
                container.conversationRepository.getConversation(conversationId)?.workspaceId
            }
            // (end of Milestone 3 registrations)
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
            terminalManager = container.terminalManager,
            browserManager = container.browserManager,
            contextEngine = contextEngine,
            attachmentStore = container.attachmentStore
        ) as T
    }
}
