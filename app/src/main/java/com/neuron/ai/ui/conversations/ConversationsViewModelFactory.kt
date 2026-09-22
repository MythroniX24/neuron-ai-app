package com.neuron.ai.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.di.AppContainer

class ConversationsViewModelFactory(private val container: AppContainer) :
    ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ConversationsViewModel::class.java)) {
            "Unknown ViewModel: $modelClass"
        }
        return ConversationsViewModel(
            repository = container.conversationRepository,
            dispatchers = container.dispatchers
        ) as T
    }
}
