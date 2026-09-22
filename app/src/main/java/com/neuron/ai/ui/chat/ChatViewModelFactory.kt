package com.neuron.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.di.AppContainer

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
            dispatchers = container.dispatchers,
            logger = container.logger
        ) as T
    }
}
