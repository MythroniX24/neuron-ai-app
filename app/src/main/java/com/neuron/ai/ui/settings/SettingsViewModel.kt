package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.settings.ThemeMode
import com.neuron.ai.data.memory.MemoryManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val memoryManager: MemoryManager?,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.LIGHT
        )

    /** Memory entries for inspection; null manager renders an empty list. */
    val memoryEntries: StateFlow<List<com.neuron.ai.core.memory.MemoryEntry>> =
        (memoryManager?.entries ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** Master switch for the whole memory system. */
    val memoryEnabled: StateFlow<Boolean> =
        (memoryManager?.enabled ?: kotlinx.coroutines.flow.flowOf(false))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch(dispatchers.io) {
            settings.setThemeMode(mode)
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        val manager = memoryManager ?: return
        viewModelScope.launch(dispatchers.io) { manager.setEnabled(enabled) }
    }

    fun deleteMemory(entryId: String) {
        val manager = memoryManager ?: return
        viewModelScope.launch(dispatchers.io) { manager.delete(entryId) }
    }

    fun clearAllMemory() {
        val manager = memoryManager ?: return
        viewModelScope.launch(dispatchers.io) { manager.clearAll() }
    }
}
