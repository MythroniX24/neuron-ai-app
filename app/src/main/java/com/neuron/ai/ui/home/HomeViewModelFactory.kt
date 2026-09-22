package com.neuron.ai.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.di.AppContainer

class HomeViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            "Unknown ViewModel: $modelClass"
        }
        return HomeViewModel(
            conversations = container.conversationRepository,
            settings = container.settingsRepository,
            dispatchers = container.dispatchers,
            logger = container.logger
        ) as T
    }
}
