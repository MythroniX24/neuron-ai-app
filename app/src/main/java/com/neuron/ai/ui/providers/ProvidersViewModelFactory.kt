package com.neuron.ai.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.di.AppContainer

class ProvidersViewModelFactory(private val container: AppContainer) :
    ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ProvidersViewModel::class.java)) {
            "Unknown ViewModel: $modelClass"
        }
        return ProvidersViewModel(
            providers = container.providerRepository,
            dispatchers = container.dispatchers
        ) as T
    }
}
