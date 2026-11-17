package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.di.AppContainer

class ConnectedAppsViewModelFactory(private val container: AppContainer) :
    ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ConnectedAppsViewModel::class.java)) {
            "Unknown ViewModel: $modelClass"
        }
        return ConnectedAppsViewModel(
            repository = container.googleConnectorRepository,
            diagnostics = container.googleConnectorDiagnostics,
            dispatchers = container.dispatchers
        ) as T
    }
}
