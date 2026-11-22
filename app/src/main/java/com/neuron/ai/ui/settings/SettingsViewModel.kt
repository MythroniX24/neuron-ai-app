package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.github.GitHubRepository
import com.neuron.ai.core.integration.google.GoogleConnectorRepository
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.settings.ThemeMode
import com.neuron.ai.core.update.AppUpdateCheckState
import com.neuron.ai.data.update.AppUpdateManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val googleRepository: GoogleConnectorRepository,
    private val gitHubRepository: GitHubRepository,
    private val dispatchers: DispatcherProvider,
    private val updateManager: AppUpdateManager? = null
) : ViewModel() {

    val isUpdateAvailable: StateFlow<Boolean> = (updateManager?.checkState?.map {
        it is AppUpdateCheckState.Available
    } ?: flowOf(false)).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.LIGHT
        )

    val isConnectorsConnected: StateFlow<Boolean> = combine(
        googleRepository.accounts,
        gitHubRepository.account
    ) { google, github ->
        google.isNotEmpty() || github != null
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch(dispatchers.io) {
            settings.setThemeMode(mode)
        }
    }
}
