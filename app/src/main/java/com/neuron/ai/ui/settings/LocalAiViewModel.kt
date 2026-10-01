package com.neuron.ai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.di.AppContainer
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.LocalModelRepository
import com.neuron.ai.data.local.RecommendedModels
import com.neuron.ai.data.local.RecommendedModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Local AI screen. */
data class LocalAiUiState(
    val models: List<LocalModelRecord> = emptyList(),
    val loadState: LocalLoadState = LocalLoadState.Idle,
    val storageUsedBytes: Long = 0L,
    val totalRamBytes: Long = 0L,
    val freeRamBytes: Long = 0L,
    /** One-shot import result notice (success or the actual failure reason). */
    val importNotice: String? = null,
    val benchmarkNotice: String? = null,
    /** true while an import is being copied/validated. */
    val importing: Boolean = false,
    /** Model id being benchmarked right now. */
    val benchmarkingId: String? = null
)

/**
 * Local AI screen: downloaded/imported models, ticks, import from file,
 * recommended catalog, storage summary, delete, benchmark. Mirrors the
 * repository state; the repository owns ALL real behavior.
 */
class LocalAiViewModel(
    private val repository: LocalModelRepository,
    private val appContext: Context?
) : ViewModel() {

    private val _state = MutableStateFlow(LocalAiUiState())
    val state: StateFlow<LocalAiUiState> = _state.asStateFlow()

    init {
        val (total, free) = repository.deviceMemory()
        viewModelScope.launch {
            repository.models.collect { records ->
                _state.value = _state.value.copy(
                    models = records,
                    storageUsedBytes = repository.storageUsedBytes(),
                    totalRamBytes = total,
                    freeRamBytes = repository.deviceMemory().second
                )
            }
        }
        viewModelScope.launch {
            repository.loadState.collect { load ->
                _state.value = _state.value.copy(loadState = load)
            }
        }
    }

    /** Curated list; the screen shows it before the user types anything. */
    val recommended: List<RecommendedModel> = RecommendedModels.all

    fun fitFor(model: RecommendedModel): RecommendedModels.Fit {
        val s = _state.value
        return RecommendedModels.fitForDevice(model, s.totalRamBytes, s.freeRamBytes)
    }

    /** Import a user-picked GGUF (validation + registration inside repository). */
    fun import(uri: android.net.Uri) {
        val context = appContext ?: return
        _state.value = _state.value.copy(importing = true, importNotice = null)
        viewModelScope.launch {
            val result = repository.importFromUri(uri, context.contentResolver)
            _state.value = _state.value.copy(
                importing = false,
                importNotice = result.fold(
                    onSuccess = { "Imported ${it.displayName} (${formatBytes(it.sizeBytes)})" },
                    onFailure = { "Import failed: ${it.message}" }
                )
            )
        }
    }

    /** "Enable for chat" tick — reflects in the header switcher immediately. */
    fun setEnabled(modelId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabledForChat(modelId, enabled) }
    }

    fun loadNow(modelId: String) {
        viewModelScope.launch { repository.ensureLoaded(modelId) }
    }

    fun delete(modelId: String) {
        viewModelScope.launch { repository.delete(modelId) }
    }

    fun benchmark(modelId: String) {
        if (_state.value.benchmarkingId != null) return
        _state.value = _state.value.copy(benchmarkingId = modelId, benchmarkNotice = null)
        viewModelScope.launch {
            val result = repository.benchmark(modelId)
            _state.value = _state.value.copy(
                benchmarkingId = null,
                benchmarkNotice = result.fold(
                    onSuccess = { "%.1f tokens/sec on this device".format(it) },
                    onFailure = { "Benchmark failed: ${it.message}" }
                )
            )
        }
    }

    fun clearImportNotice() { _state.value = _state.value.copy(importNotice = null) }
    fun clearBenchmarkNotice() { _state.value = _state.value.copy(benchmarkNotice = null) }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LocalAiViewModel(container.localModelRepository, container.appContext) as T
    }
}
