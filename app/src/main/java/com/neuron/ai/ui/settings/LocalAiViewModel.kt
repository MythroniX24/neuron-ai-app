package com.neuron.ai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.di.AppContainer
import com.neuron.ai.data.local.HfHubClient
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.LocalModelRepository
import com.neuron.ai.data.local.ModelDownloadManager
import com.neuron.ai.data.local.RecommendedModels
import com.neuron.ai.data.local.RecommendedModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One search hit enriched with the repo's GGUF variants. */
data class SearchHit(
    val repoId: String,
    val downloads: Long,
    val likes: Long,
    val license: String?,
    val variants: List<HfHubClient.SearchResult.Variant>,
    val loadingVariants: Boolean = false
)

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
    val benchmarkingId: String? = null,
    // ---- Hub search + downloads (milestones 3-4) ----
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchHit> = emptyList(),
    val searchError: String? = null,
    val downloads: List<ModelDownloadManager.Download> = emptyList(),
    val wifiOnly: Boolean = true
)

/**
 * Local AI screen: downloaded/imported models, ticks, import from file,
 * recommended catalog, storage summary, delete, benchmark. Mirrors the
 * repository state; the repository owns ALL real behavior.
 */
class LocalAiViewModel(
    private val repository: LocalModelRepository,
    private val appContext: Context?,
    private val hubClient: HfHubClient = HfHubClient(),
    private val downloadManager: ModelDownloadManager? = null
) : ViewModel() {

    private val _state = MutableStateFlow(LocalAiUiState())
    val state: StateFlow<LocalAiUiState> = _state.asStateFlow()

    init {
        val (total, free) = repository.deviceMemory()
        _state.value = _state.value.copy(wifiOnly = downloadManager?.wifiOnly ?: true)
        downloadManager?.let { dm ->
            viewModelScope.launch {
                dm.downloads.collect { list ->
                    _state.value = _state.value.copy(downloads = list)
                }
            }
        }
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

    // ---- Hub search + downloads (milestones 3-4) ----------------------------

    /**
     * Updates the query and fires a debounced search. EMPTY query clears the
     * results so the curated Recommended section takes over again.
     */
    fun onSearchQueryChange(query: String) {
        _state.value = _state.value.copy(searchQuery = query, searchError = null)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(
                searchResults = emptyList(), searching = false
            )
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400) // debounce
            runSearch(query)
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    private suspend fun runSearch(query: String) {
        _state.value = _state.value.copy(searching = true)
        val results = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                hubClient.search(query, limit = 15)
            }
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                searching = false,
                searchResults = emptyList(),
                searchError = "Search failed: ${t.message}"
            )
            return
        }
        val hits = results.map { hit ->
            SearchHit(hit.repoId, hit.downloads, hit.likes, hit.license, emptyList(), true)
        }
        _state.value = _state.value.copy(searching = false, searchResults = hits)
        // Enrich each hit with its GGUF variants (grouped under one entry —
        // the user picks the quantization at download time).
        results.forEach { hit ->
            launch {
                val variants = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        hubClient.files(hit.repoId)
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
                _state.value = _state.value.copy(
                    searchResults = _state.value.searchResults.map {
                        if (it.repoId == hit.repoId) {
                            it.copy(variants = variants, loadingVariants = false)
                        } else it
                    }
                )
            }
        }
    }

    fun clearSearchError() { _state.value = _state.value.copy(searchError = null) }

    fun startDownload(repoId: String, variant: HfHubClient.SearchResult.Variant, displayName: String) {
        downloadManager?.start(repoId, variant, displayName)
    }

    fun pauseDownload(id: String) { downloadManager?.pause(id) }
    fun resumeDownload(id: String) { downloadManager?.resume(id) }
    fun cancelDownload(id: String) { downloadManager?.cancel(id) }

    fun setWifiOnly(enabled: Boolean) {
        downloadManager?.wifiOnly = enabled
        _state.value = _state.value.copy(wifiOnly = enabled)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LocalAiViewModel(
                repository = container.localModelRepository,
                appContext = container.appContext,
                hubClient = container.hfHubClient,
                downloadManager = container.downloadManager
            ) as T
    }
}
