package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.data.local.GgufReader
import com.neuron.ai.data.local.LocalEngineLoader
import com.neuron.ai.data.local.LocalModelRepository
import com.neuron.ai.data.local.VisionProjector
import com.neuron.ai.di.AppContainer
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs Settings → Debug console. Collects everything that explains a
 * local-model failure — build flags, device memory, the actual .gguf files
 * (re-parsed here so a per-file parse error is visible), and llama.cpp's own
 * captured log — on the IO dispatcher, never the main thread.
 */
class DebugConsoleViewModel(
    private val repository: LocalModelRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _snapshot = MutableStateFlow<DebugSnapshot?>(null)
    val snapshot: StateFlow<DebugSnapshot?> = _snapshot.asStateFlow()

    /** One .gguf in the models directory, with what the parser makes of it. */
    data class FileReport(
        val name: String,
        val detail: String,
        /** true when the header could not be read — the likely culprit. */
        val suspect: Boolean
    )

    data class DebugSnapshot(
        val nativeLibraryLoaded: Boolean,
        val engineVersion: String,
        val gpuAvailable: Boolean,
        val visionAvailable: Boolean,
        val registeredModels: Int,
        val totalRamGb: String,
        val freeRamGb: String,
        val modelsOnDisk: String,
        val storageUsed: String,
        val modelFiles: List<FileReport>,
        val logText: String
    )

    fun refresh() {
        viewModelScope.launch { _snapshot.value = collect() }
    }

    /** Drops the native log ring buffer; call off the main thread. */
    suspend fun clearLog() = withContext(dispatchers.io) {
        LocalEngineLoader.clearDiagnosticsLog()
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
            gpuAvailable = engine.gpuAvailable,
            visionAvailable = engine.visionAvailable,
            registeredModels = models.size,
            totalRamGb = gb(totalRam),
            freeRamGb = gb(freeRam),
            modelsOnDisk = "${files.size} file(s)",
            storageUsed = gb(repository.storageUsedBytes()),
            modelFiles = files,
            logText = engine.logText
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
        DebugConsoleViewModel(container.localModelRepository, container.dispatchers) as T
}