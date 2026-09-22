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
            importAttachmentFn = { uri -> container.attachmentStore.importFromUri(uri) }
        ) as T
    }
}
