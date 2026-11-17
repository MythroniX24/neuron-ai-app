package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.diagnostics.DiagnosticEntry
import com.neuron.ai.core.diagnostics.DiagnosticsLog
import com.neuron.ai.core.settings.ResponseSpacing
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.data.google.BackendHealth
import com.neuron.ai.data.google.GoogleConnectorDiagnostics
import com.neuron.ai.data.local.GgufReader
import com.neuron.ai.data.local.LocalEngineLoader
import com.neuron.ai.data.local.LocalModelRepository
import com.neuron.ai.data.local.VisionProjector
import com.neuron.ai.data.web.ImageResolutionStats
import com.neuron.ai.data.web.WikipediaImageResolver
import com.neuron.ai.di.AppContainer
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs Settings → Debug console.
 *
 * Collects everything that explains a failure without logcat and without
 * guesswork: the local engine (build flags, device memory, real .gguf files,
 * llama.cpp's captured log), the Google connectors (installed APK signing
 * SHA-1, client id, backend, last consent status code, live probe), the
 * card-image pipeline (lookup counters, misses, provider timeouts) and the
 * reply-layout settings that are actually in effect. Everything disk or
 * network bound runs on the IO dispatcher.
 */
class DebugConsoleViewModel(
    private val repository: LocalModelRepository,
    private val dispatchers: DispatcherProvider,
    private val settings: SettingsRepository? = null,
    private val googleDiagnostics: GoogleConnectorDiagnostics? = null,
    private val imageResolver: WikipediaImageResolver? = null,
    private val diagnostics: DiagnosticsLog? = null
) : ViewModel() {

    private val _snapshot = MutableStateFlow<DebugSnapshot?>(null)
    val snapshot: StateFlow<DebugSnapshot?> = _snapshot.asStateFlow()

    private val _health = MutableStateFlow<BackendHealth?>(null)
    val health: StateFlow<BackendHealth?> = _health.asStateFlow()

    /** When the user opens the console right after a failed local load,
     *  pass the failed model so the screen can call it out.
     *  The model may have been unregistered by the time we run — that's fine. */
    var failedLoadHint: FailedLoadReference? = null
        private set

    /** One .gguf in the models directory, with what the parser makes of it. */
    data class FileReport(
        val name: String,
        val detail: String,
        /** true when the header could not be read — the likely culprit. */
        val suspect: Boolean
    )

    /** Google connector facts, mirrored for the screen. */
    data class ConnectorReport(
        val configured: Boolean,
        val webClientId: String,
        val backendUrl: String,
        val signingSha1: String?,
        val accounts: List<GoogleConnectorDiagnostics.AccountReport>,
        val lastSignInError: String?
    )

    data class DebugSnapshot(
        val nativeLibraryLoaded: Boolean,
        val engineVersion: String,
        val backends: String,
        val gpuAvailable: Boolean,
        val visionAvailable: Boolean,
        val registeredModels: Int,
        val totalRamGb: String,
        val freeRamGb: String,
        val modelsOnDisk: String,
        val storageUsed: String,
        val modelFiles: List<FileReport>,
        val logText: String,
        /** When the user arrived here right after a local-model load failure,
         *  this names the model that failed (if it is still registered) and a
         *  short human reason — surfaced as a banner so the llama.cpp log is
         *  the obvious thing to read.
         */
        val failedLoad: FailedLoadReference? = null,
        /** Google connectors / OAuth state. */
        val connector: ConnectorReport? = null,
        /** Card-image pipeline counters. */
        val imageStats: ImageResolutionStats? = null,
        /** Default line spacing the user picked. */
        val lineSpacing: ResponseSpacing = ResponseSpacing.COMFORTABLE,
        /** Default paragraph gap the user picked (independent of line spacing). */
        val paragraphSpacing: ResponseSpacing = ResponseSpacing.COMFORTABLE,
        /** Newest-last in-app diagnostics feed. */
        val diagnosticEntries: List<DiagnosticEntry> = emptyList()
    )

    /** Optional context for the "arrived here after a crash" flow. */
    data class FailedLoadReference(
        val modelId: String,
        val displayName: String,
        val reason: String
    )

    fun refresh() {
        viewModelScope.launch { _snapshot.value = collect() }
    }

    fun setFailedLoadHint(modelId: String, displayName: String, reason: String) {
        failedLoadHint = FailedLoadReference(modelId, displayName, reason)
        refresh()
    }

    /** Drops the native log ring buffer; call off the main thread. */
    suspend fun clearLog() = withContext(dispatchers.io) {
        LocalEngineLoader.clearDiagnosticsLog()
        diagnostics?.clear()
    }

    /** Live probe of the connector Worker; the result lands in [health]. */
    fun probeBackend() {
        val google = googleDiagnostics ?: return
        viewModelScope.launch(dispatchers.io) {
            _health.value = google.checkBackend()
            _snapshot.value = collect()
        }
    }

    private suspend fun collect(): DebugSnapshot = withContext(dispatchers.io) {
        val engine = LocalEngineLoader.diagnostics()
        val (totalRam, freeRam) = repository.deviceMemory()
        val models = repository.models.value
        val files = (repository.modelsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            .sortedBy { it.name }
            .map { file -> report(file, models.any { it.fileName == file.name }) }
        DebugSnapshot(
            nativeLibraryLoaded = engine.nativeLibraryLoaded,
            engineVersion = engine.engineVersion,
            backends = engine.backends,
            gpuAvailable = engine.gpuAvailable,
            visionAvailable = engine.visionAvailable,
            registeredModels = models.size,
            totalRamGb = gb(totalRam),
            freeRamGb = gb(freeRam),
            modelsOnDisk = "${files.size} file(s)",
            storageUsed = gb(repository.storageUsedBytes()),
            modelFiles = files,
            logText = engine.logText,
            failedLoad = failedLoadHint,
            connector = googleDiagnostics?.snapshot()?.let { snapshot ->
                ConnectorReport(
                    configured = snapshot.configured,
                    webClientId = snapshot.webClientId,
                    backendUrl = snapshot.backendUrl,
                    signingSha1 = snapshot.build.signingSha1,
                    accounts = snapshot.accounts,
                    lastSignInError = snapshot.lastSignInError
                )
            },
            imageStats = imageResolver?.stats?.value,
            lineSpacing = runCatching {
                settings?.replyLineSpacing?.first() ?: ResponseSpacing.COMFORTABLE
            }.getOrDefault(ResponseSpacing.COMFORTABLE),
            paragraphSpacing = runCatching {
                settings?.replyParagraphSpacing?.first() ?: ResponseSpacing.COMFORTABLE
            }.getOrDefault(ResponseSpacing.COMFORTABLE),
            diagnosticEntries = diagnostics?.snapshot().orEmpty()
        )
    }

    /**
     * Re-parses one file. The interesting part is the FAILURE text: it says
     * exactly where the header went wrong, which is what the user asked to
     * be able to see.
     */
    private fun report(file: File, registered: Boolean): FileReport {
        val size = humanSize(file.length())
        val projector = VisionProjector.isProjectorFile(file.name)
        return try {
            val info = GgufReader.parse(file)
            FileReport(
                name = file.name,
                detail = buildString {
                    append(size)
                    append(" • v").append(info.version)
                    append(" • ").append(info.architecture ?: "unknown arch")
                    info.quantization?.let { append(" • ").append(it) }
                    append(" • ").append(info.metadataKVCount).append(" metadata keys")
                    append(" • ctx ").append(info.contextLength ?: 0)
                    if (projector) append(" • vision projector")
                    if (registered) append(" • registered") else append(" • NOT registered")
                },
                suspect = false
            )
        } catch (t: Throwable) {
            FileReport(
                name = file.name,
                detail = "$size • HEADER PROBLEM: ${t.message ?: t::class.simpleName}"
                        + (if (registered) " • registered" else " • NOT registered"),
                suspect = true
            )
        }
    }

    /** Plain-text dump for the Copy report button. */
    fun reportText(data: DebugSnapshot): String = buildString {
        appendLine("Neuron-AI debug report")
        appendLine("engine: ${data.engineVersion}")
        appendLine("native library: ${if (data.nativeLibraryLoaded) "loaded" else "NOT LOADED"}")
        appendLine("backends loaded: ${data.backends}")
        appendLine("gpu (vulkan): ${data.gpuAvailable}")
        appendLine("vision loaded: ${data.visionAvailable}")
        appendLine("ram: ${data.totalRamGb} total / ${data.freeRamGb} free")
        appendLine("models: ${data.registeredModels} registered, ${data.modelsOnDisk}, ${data.storageUsed}")
        appendLine()
        appendLine("Model files:")
        if (data.modelFiles.isEmpty()) appendLine("  (none)")
        data.modelFiles.forEach { appendLine("  ${it.name} — ${it.detail}") }
        appendLine()
        appendLine("llama.cpp log:")
        appendLine(if (data.logText.isBlank()) "  (empty)" else data.logText.trimEnd())
        data.connector?.let { connector ->
            appendLine()
            appendLine("Google connectors:")
            appendLine("  configured: ${connector.configured}")
            appendLine("  web client: ${connector.webClientId}")
            appendLine("  backend: ${connector.backendUrl}")
            appendLine("  signing SHA-1: ${connector.signingSha1 ?: "unavailable"}")
            appendLine("  last sign-in error: ${connector.lastSignInError ?: "none"}")
            if (connector.accounts.isEmpty()) appendLine("  accounts: (none)")
            connector.accounts.forEach { account ->
                appendLine(
                    "  account: ${account.email ?: account.id} — " +
                        account.services.joinToString(", ") { it.label }.ifBlank { "no service scope" }
                )
            }
            _health.value?.let { health ->
                appendLine("  backend probe: ${health.status} in ${health.latencyMs}ms — ${health.detail}")
            }
        }
        data.imageStats?.let { stats ->
            appendLine()
            appendLine("Card images: ${stats.summary}")
        }
        appendLine()
        appendLine("Reply layout: lines ${data.lineSpacing.label}, paragraph gap ${data.paragraphSpacing.label}")
        if (data.diagnosticEntries.isNotEmpty()) {
            appendLine()
            appendLine("Diagnostics log (newest last):")
            data.diagnosticEntries.takeLast(60).forEach { appendLine("  ${it.asLine()}") }
        }
        data.failedLoad?.let { failed ->
            appendLine()
            appendLine("FAILED LOAD (auto-diagnostics):")
            appendLine("  model: ${failed.displayName} (${failed.modelId})")
            appendLine("  reason: ${failed.reason}")
        }
    }

    private fun gb(bytes: Long): String {
        val value = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.2f GB", value)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "$bytes B"
    }
}

class DebugConsoleViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DebugConsoleViewModel(
            repository = container.localModelRepository,
            dispatchers = container.dispatchers,
            settings = container.settingsRepository,
            googleDiagnostics = container.googleConnectorDiagnostics,
            imageResolver = container.comparisonImageResolver,
            diagnostics = container.diagnostics
        ) as T
}
