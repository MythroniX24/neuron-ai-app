package com.neuron.ai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.di.AppContainer
import com.neuron.ai.data.local.DeviceHealthMonitor
import com.neuron.ai.data.local.HfHubClient
import com.neuron.ai.data.local.LocalEngineLoader
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.LocalModelRepository
import com.neuron.ai.data.local.ModelDownloadManager
import com.neuron.ai.data.local.RecommendedModels
import com.neuron.ai.data.local.RecommendedModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One search hit enriched with the repo's GGUF variants. */
data class SearchHit(
    val repoId: String,
    val downloads: Long,
    val likes: Long,
    val license: String?,
    val variants: List<HfHubClient.SearchResult.Variant>,
    val loadingVariants: Boolean = false,
    /** Why this repo has no downloadable files (gated/renamed/rate-limited). */
    val unavailableReason: String? = null
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
    val wifiOnly: Boolean = true,
    /**
     * Curated catalog WITH its live Hub listing resolved per entry (which GGUF
     * files exist right now, or why the repo can't be used). The catalog only
     * holds a quant PREFERENCE — file names drift upstream, so they are
     * resolved from the Hub instead of being hardcoded.
     */
    val recommended: List<RecommendedModel> = RecommendedModels.all,
    /** true while the catalog's file listings are being fetched. */
    val recommendLoading: Boolean = false,
    // ---- Performance (milestone 5) ----
    /** True when the BUILD + DEVICE can do Vulkan GPU offload at all. */
    val gpuAvailable: Boolean = false,
    /** User's GPU offload preference (on-device pref, default ON). */
    val useGpu: Boolean = true,
    /** CPU threads for generation; 0 = Auto (device-sized). */
    val cpuThreads: Int = 0,
    // ---- Device health / thermal throttle (milestone 7) ----
    /** Live thermal severity label ("Cool", "Hot", "Unknown"...). */
    val thermalLabel: String = "Unknown",
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
    val powerSaveMode: Boolean = false,
    /** Non-null while the device is throttling on-device generation. */
    val throttleNotice: String? = null,
    /** Threads the next generation will actually use (after throttling). */
    val effectiveThreads: Int = 0,
    /** True when the thermal policy vetoed GPU offload for the next load. */
    val gpuThrottled: Boolean = false
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
    private val downloadManager: ModelDownloadManager? = null,
    /** Milestone 7: thermal/battery watcher driving the throttle policy. */
    private val healthMonitor: DeviceHealthMonitor? = null
) : ViewModel() {

    private val _state = MutableStateFlow(LocalAiUiState())
    val state: StateFlow<LocalAiUiState> = _state.asStateFlow()

    init {
        val (total, free) = repository.deviceMemory()
        val health = repository.thermalState()
        val throttle = repository.throttleDecision()
        _state.value = _state.value.copy(
            wifiOnly = downloadManager?.wifiOnly ?: true,
            gpuAvailable = LocalEngineLoader.gpuAvailable,
            useGpu = repository.useGpu,
            cpuThreads = repository.userThreads,
            thermalLabel = health.severityLabel(),
            batteryPercent = health.batteryPercent,
            charging = health.charging,
            powerSaveMode = health.powerSaveMode,
            throttleNotice = throttle.reason,
            effectiveThreads = throttle.threads,
            gpuThrottled = throttle.throttled && !throttle.allowGpu && repository.useGpu
        )
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
        // Live Hub listing for the curated catalog: which GGUF files exist and
        // which repos are gated. Fetched here (not lazily on tap) so the Get
        // button is usable immediately.
        refreshRecommended()
        // Thermal / battery state → throttle status. Only the DISPLAY changes
        // live; the throttle itself is applied at the next model load, so an
        // answer in flight is never interrupted by a status change.
        healthMonitor?.let { monitor ->
            viewModelScope.launch {
                monitor.state.collect { health ->
                    val throttle = repository.throttleDecision()
                    _state.value = _state.value.copy(
                        thermalLabel = health.severityLabel(),
                        batteryPercent = health.batteryPercent,
                        charging = health.charging,
                        powerSaveMode = health.powerSaveMode,
                        throttleNotice = throttle.reason,
                        effectiveThreads = throttle.threads,
                        gpuThrottled = throttle.throttled && !throttle.allowGpu &&
                            repository.useGpu
                    )
                }
            }
        }
    }

    /** Curated list; the screen shows it before the user types anything. */
    val recommended: List<RecommendedModel> get() = _state.value.recommended

    /**
     * Resolves each recommended repo's GGUF files live. Runs at open time so
     * the "Get" button works on the FIRST tap (it used to be permanently
     * disabled) and gated repos are labelled instead of failing at download.
     */
    fun refreshRecommended() {
        if (_state.value.recommendLoading) return
        _state.value = _state.value.copy(recommendLoading = true)
        viewModelScope.launch {
            val resolved = RecommendedModels.all.map { model ->
                val listing = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { hubClient.listing(model.hfRepo) }.getOrElse { t ->
                        HfHubClient.RepoListing(error = t.message ?: "Could not reach Hugging Face")
                    }
                }
                model.copy(
                    variants = listing.variants,
                    unavailableReason = listing.unavailableReason
                )
            }
            _state.value = _state.value.copy(
                recommended = resolved,
                recommendLoading = false
            )
        }
    }

    /**
     * One-tap download of the recommended quant: resolves the preferred
     * quantization from the live listing and starts the transfer. Falls back to
     * refreshing the listing when the catalog has not resolved yet.
     */
    fun quickDownloadRecommended(model: RecommendedModel) {
        viewModelScope.launch {
            var entry = _state.value.recommended.firstOrNull { it.id == model.id } ?: model
            if (entry.variants.isEmpty() && entry.unavailableReason == null) {
                // Catalog listing hasn't arrived (or was cleared): fetch this
                // one repo now so a single tap always does something.
                entry = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val listing = runCatching { hubClient.listing(model.hfRepo) }.getOrElse { t ->
                        HfHubClient.RepoListing(error = t.message ?: "Could not reach Hugging Face")
                    }
                    _state.value = _state.value.copy(
                        recommended = _state.value.recommended.map {
                            if (it.id == model.id) {
                                it.copy(
                                    variants = listing.variants,
                                    unavailableReason = listing.unavailableReason
                                )
                            } else it
                        }
                    )
                    model.copy(
                        variants = listing.variants,
                        unavailableReason = listing.unavailableReason
                    )
                }
            }
            entry.unavailableReason?.let {
                _state.value = _state.value.copy(searchError = "${model.displayName}: $it")
                return@launch
            }
            val variant = HfHubClient.selectVariant(entry.variants, entry.preferredQuants)
            if (variant == null) {
                _state.value = _state.value.copy(
                    searchError = "${model.displayName}: no GGUF file could be resolved."
                )
                return@launch
            }
            startDownload(model.hfRepo, variant, model.displayName)
        }
    }

    /** Opens the quant picker for a recommended entry. */
    fun recommendedVariants(model: RecommendedModel): List<HfHubClient.SearchResult.Variant> =
        _state.value.recommended.firstOrNull { it.id == model.id }?.variants
            ?: emptyList()

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
        // coroutineScope gives the child launches a receiver and ties their
        // lifetime to searchJob so a stale query's enrichment is cancelled.
        coroutineScope {
            results.forEach { hit ->
                launch {
                    val listing = try {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            hubClient.listing(hit.repoId)
                        }
                    } catch (t: Throwable) {
                        HfHubClient.RepoListing(error = t.message ?: "Could not reach Hugging Face")
                    }
                    _state.value = _state.value.copy(
                        searchResults = _state.value.searchResults.map {
                            if (it.repoId == hit.repoId) {
                                it.copy(
                                    variants = listing.variants,
                                    loadingVariants = false,
                                    unavailableReason = listing.unavailableReason
                                )
                            } else it
                        }
                    )
                }
            }
        }
    }

    fun clearSearchError() { _state.value = _state.value.copy(searchError = null) }

    fun startDownload(repoId: String, variant: HfHubClient.SearchResult.Variant, displayName: String) {
        if (downloadManager == null) {
            _state.value = _state.value.copy(
                searchError = "Downloads are unavailable in this build."
            )
            return
        }
        downloadManager.start(repoId, variant, displayName)
    }

    fun pauseDownload(id: String) { downloadManager?.pause(id) }
    fun resumeDownload(id: String) { downloadManager?.resume(id) }
    fun cancelDownload(id: String) { downloadManager?.cancel(id) }

    fun setWifiOnly(enabled: Boolean) {
        downloadManager?.wifiOnly = enabled
        _state.value = _state.value.copy(wifiOnly = enabled)
    }

    // ---- Performance (milestone 5) -------------------------------------

    /**
     * Toggles GPU (Vulkan) offload. Applies IMMEDIATELY: the active model is
     * reloaded under the new setting, so the next token is generated the new
     * way. A failed reload falls back to CPU automatically (repository logic).
     */
    fun setUseGpu(enabled: Boolean) {
        repository.useGpu = enabled
        _state.value = _state.value.copy(useGpu = enabled)
        applyPerfChange()
    }

    /** Sets generation CPU threads (0 = Auto) and reloads if a model is active. */
    fun setCpuThreads(threads: Int) {
        repository.userThreads = threads
        _state.value = _state.value.copy(cpuThreads = threads)
        applyPerfChange()
    }

    /**
     * Reload of the ACTIVE model after a perf change — a perf change without
     * a reload would silently do nothing (context params are set at load).
     */
    private fun applyPerfChange() {
        val active = _state.value.loadState
        if (active is LocalLoadState.Ready) {
            viewModelScope.launch { repository.ensureLoaded(active.modelId) }
        }
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
                downloadManager = container.downloadManager,
                healthMonitor = container.deviceHealthMonitor
            ) as T
    }
}
