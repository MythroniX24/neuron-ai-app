package com.neuron.ai.data.local

import android.app.ActivityManager
import android.content.Context
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owns on-device models: a workspace-isolated models directory, GGUF-validated
 * imports, enable ticks, the single-active load orchestration (unload previous
 * → load new → on failure keep consistent), storage summary and delete.
 * Persistence is a JSON manifest in app-private storage.
 */
class LocalModelRepository(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger?,
    /** Milestone 7: thermal/battery watcher; null = no thermal protection. */
    private val healthMonitor: DeviceHealthMonitor? = null
) {

    /** Workspace-isolated models directory — nothing outside it is touched. */
    val modelsDir: File = File(context.filesDir, "local-models").apply { mkdirs() }

    private val manifestFile = File(context.filesDir, "local-models.json")
    /** Performance prefs (GPU on/off, CPU threads) — tiny JSON, defaults below. */
    private val perfPrefsFile = File(context.filesDir, "local-perf.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _models = MutableStateFlow<List<LocalModelRecord>>(emptyList())
    val models: StateFlow<List<LocalModelRecord>> = _models.asStateFlow()

    private val _loadState = MutableStateFlow<LocalLoadState>(LocalLoadState.Idle)
    val loadState: StateFlow<LocalLoadState> = _loadState.asStateFlow()

    /** Serializes load/unload/benchmark — never two engines racing. */
    private val engineMutex = Mutex()

    init {
        loadManifest()
    }

    // ---- Manifest ----------------------------------------------------------

    private fun loadManifest() {
        _models.value = try {
            if (manifestFile.exists()) {
                json.decodeFromString<List<LocalModelRecord>>(manifestFile.readText())
            } else {
                emptyList()
            }
        } catch (t: Throwable) {
            logger?.w("LocalModels", "Manifest unreadable — starting empty", t)
            emptyList()
        }
    }

    private fun persist() {
        try {
            manifestFile.writeText(json.encodeToString<List<LocalModelRecord>>(_models.value))
        } catch (t: Throwable) {
            logger?.w("LocalModels", "Manifest persist failed", t)
        }
    }

    // ---- Import ------------------------------------------------------------

    /**
     * Imports a user-picked GGUF file: copies it into the isolated models
     * directory, validates the FORMAT (magic + header + metadata, not the
     * extension) and a quick native load test, then registers it Available.
     * Returns the new record, or the actual failure reason — never silent.
     */
    suspend fun importFromUri(uri: android.net.Uri, resolver: android.content.ContentResolver): Result<LocalModelRecord> =
        withContext(dispatchers.io) {
            try {
                val temp = File.createTempFile("import-", ".gguf", context.cacheDir)
                resolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext Result.failure(
                    IOException("Could not read the picked file")
                )
                try {
                    importFile(temp, displayName = temp.name.removeSuffix(".gguf"))
                } finally {
                    temp.delete()
                }
            } catch (t: Throwable) {
                logger?.w("LocalModels", "Import failed", t)
                Result.failure(t)
            }
        }

    /** Import from a plain file (also used by tests and the download manager later). */
    suspend fun importFile(sourceFile: File, displayName: String): Result<LocalModelRecord> =
        withContext(dispatchers.io) {
            // Format check FIRST — not just the extension.
            if (!GgufReader.looksLikeGguf(sourceFile)) {
                return@withContext Result.failure(
                    GgufReader.InvalidGgufException(
                        "This file is not a GGUF model (missing GGUF magic). Import cancelled."
                    )
                )
            }
            val info = try {
                GgufReader.parse(sourceFile)
            } catch (t: Throwable) {
                return@withContext Result.failure(
                    IllegalArgumentException("Corrupt or unsupported GGUF: ${t.message}")
                )
            }

            // Path-safety: the stored name is flattened — no traversal.
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val dest = File(modelsDir, "$safeName-${UUID.randomUUID().toString().take(8)}.gguf")

            try {
                sourceFile.copyTo(dest, overwrite = false)
                val record = LocalModelRecord(
                    id = "local-" + UUID.randomUUID().toString().take(8),
                    displayName = displayName.take(60),
                    fileName = dest.name,
                    sizeBytes = dest.length(),
                    quantization = info.quantization,
                    architecture = info.architecture,
                    contextLength = info.contextLength,
                    blockCount = info.blockCount,
                    source = LocalModelRecord.SOURCE_IMPORT,
                    importedAtEpochMs = System.currentTimeMillis(),
                    supportsTools = info.declaresToolCalling
                )
                _models.value = _models.value + record
                persist()
                Result.success(record)
            } catch (t: Throwable) {
                dest.delete()
                Result.failure(t)
            }
        }

    /**
     * Registers a file ALREADY inside the models directory (download-manager
     * path) — validates and registers WITHOUT re-copying GB-sized files.
     */
    suspend fun registerExistingFile(file: File, displayName: String, source: String): Result<LocalModelRecord> =
        withContext(dispatchers.io) {
            if (!GgufReader.looksLikeGguf(file)) {
                return@withContext Result.failure(
                    GgufReader.InvalidGgufException("Downloaded file is not a valid GGUF model")
                )
            }
            val info = try {
                GgufReader.parse(file)
            } catch (t: Throwable) {
                return@withContext Result.failure(
                    IllegalArgumentException("Corrupt or unsupported GGUF: ${t.message}")
                )
            }
            val record = LocalModelRecord(
                id = "local-" + UUID.randomUUID().toString().take(8),
                displayName = displayName.take(60),
                fileName = file.name,
                sizeBytes = file.length(),
                quantization = info.quantization,
                architecture = info.architecture,
                contextLength = info.contextLength,
                blockCount = info.blockCount,
                source = source,
                importedAtEpochMs = System.currentTimeMillis(),
                supportsTools = info.declaresToolCalling
            )
            _models.value = _models.value + record
            persist()
            Result.success(record)
        }

    // ---- Enable ticks ------------------------------------------------------

    /** Ticking/unticking reflects in the header switcher immediately. */
    suspend fun setEnabledForChat(modelId: String, enabled: Boolean) = withContext(dispatchers.io) {
        _models.value = _models.value.map {
            if (it.id == modelId) it.copy(enabledForChat = enabled) else it
        }
        persist()
        // Unticking the ACTIVE model: fall back to unloaded — the chat falls
        // back to its default cloud model on the next resolveSelection().
        if (!enabled) {
            val active = _loadState.value
            if (active is LocalLoadState.Ready && active.modelId == modelId) {
                unload()
            }
        }
        Unit
    }

    // ---- Performance prefs (milestone 5) -----------------------------------

    /**
     * GPU (Vulkan) offload preference. Defaults ON — with the CPU retry below
     * it degrades gracefully on devices where the GPU path fails, and every
     * load is validated so a driver crash never leaves a half-loaded model.
     * Backed by a 1-line pref file, not the model manifest.
     */
    var useGpu: Boolean
        get() = perfPref("gpu")?.toBooleanStrictOrNull() ?: true
        set(value) = writePerfPref("gpu", value.toString())

    /**
     * User-selected CPU thread count for generation. 0 = Auto (device-sized
     * default, see [defaultThreads]). Coerced to a sane 1..16 on write.
     */
    var userThreads: Int
        get() = perfPref("threads")?.toIntOrNull() ?: 0
        set(value) = writePerfPref("threads", value.coerceIn(0, 16).toString())

    private fun perfPref(key: String): String? = try {
        if (perfPrefsFile.exists()) {
            // Tiny "key=value" lines — no serialization ceremony for 2 prefs.
            perfPrefsFile.readLines()
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
        } else null
    } catch (_: Throwable) {
        null
    }

    private fun writePerfPref(key: String, value: String) {
        try {
            val others = if (perfPrefsFile.exists()) {
                perfPrefsFile.readLines().filterNot { it.startsWith("$key=") || it.isBlank() }
            } else emptyList()
            perfPrefsFile.writeText((others + "$key=$value").joinToString("\n"))
        } catch (t: Throwable) {
            logger?.w("LocalModels", "Perf pref write failed", t)
        }
    }

    /** Threads actually used: user choice when set, device default otherwise. */
    private fun resolvedThreads(): Int =
        if (userThreads in 1..16) userThreads else defaultThreads()

    // ---- Thermal / battery throttle (milestone 7) --------------------------

    /** Live device-health snapshot (nominal-ish defaults when unsupported). */
    fun thermalState(): DeviceThermalState =
        healthMonitor?.state?.value ?: DeviceThermalState()

    /**
     * The load the NEXT generation will actually run: user prefs filtered
     * through [LocalThrottlePolicy] so a hot or low-battery device silently
     * gets fewer threads / no GPU instead of melting down mid-answer.
     */
    fun throttleDecision(): ThrottleDecision =
        LocalThrottlePolicy.decide(
            state = thermalState(),
            wantsGpu = useGpu && LocalEngineLoader.gpuAvailable,
            requestedThreads = resolvedThreads()
        )

    /** Token ceiling for the next turn (thermal-aware). */
    fun maxOutputTokens(): Int = throttleDecision().maxOutputTokens

    /** One-line throttle reason for the UI; null when running unthrottled. */
    fun throttleNotice(): String? = throttleDecision().reason

    /**
     * Deliberately NO auto-reload on a throttle change: context/thread/GPU
     * params are fixed at load time, and reloading mid-generation would abort
     * an answer the user is reading. The throttle therefore applies at the
     * NEXT load — which the Settings UI states out loud ("Next answer: 2
     * threads"), and the chat timeline repeats per turn.
     */

    // ---- Load orchestration ------------------------------------------------

    /**
     * Loads [modelId] as THE active model: the previous one is fully unloaded
     * first (single-active constraint). When GPU offload is enabled but the
     * load fails (driver, shader or GPU OOM), it retries ONCE on CPU so the
     * user keeps a working model instead of a hard failure. On total failure
     * the state is consistent — unloaded with the reason surfaced.
     */
    suspend fun ensureLoaded(modelId: String): Result<Unit> = withContext(dispatchers.io) {
        engineMutex.withLock {
            val record = _models.value.firstOrNull { it.id == modelId }
                ?: return@withLock Result.failure(IllegalArgumentException("Unknown local model"))
            val active = _loadState.value
            if (active is LocalLoadState.Ready && active.modelId == modelId) {
                return@withLock Result.success(Unit)
            }

            _loadState.value = LocalLoadState.Loading(modelId, record.displayName)
            val result = loadEngine(record)
            _loadState.value = when (result) {
                is LocalEngineLoader.LoadResult.Success -> LocalLoadState.Ready(modelId)
                is LocalEngineLoader.LoadResult.Failure -> {
                    logger?.w("LocalModels", "Load failed: ${result.reason}")
                    LocalLoadState.Failed(modelId, result.reason)
                }
            }
            if (result is LocalEngineLoader.LoadResult.Success) Result.success(Unit)
            else Result.failure(IllegalStateException((result as LocalEngineLoader.LoadResult.Failure).reason))
        }
    }

    /**
     * One load attempt with the CURRENT performance prefs (GPU offload when
     * enabled AND the device probe passes), plus a single CPU retry when the
     * GPU path was requested and failed. Never leaves a half-loaded engine.
     */
    private fun loadEngine(record: LocalModelRecord): LocalEngineLoader.LoadResult {
        val path = File(modelsDir, record.fileName).absolutePath
        // Milestone 6: the GGUF's declared context, capped to what a phone can
        // hold. Milestone 7: threads + GPU filtered through the throttle policy.
        val contextTokens = LocalModelRouter.effectiveContextTokens(record.contextLength)
        val throttle = throttleDecision()
        val wantGpu = throttle.allowGpu
        var result = LocalEngineLoader.load(
            path = path,
            contextTokens = contextTokens,
            threads = throttle.threads,
            useGpu = wantGpu
        )
        if (result is LocalEngineLoader.LoadResult.Failure && wantGpu) {
            logger?.w("LocalModels", "GPU load failed — retrying on CPU: ${result.reason}")
            LocalEngineLoader.unload()
            result = LocalEngineLoader.load(
                path = path,
                contextTokens = contextTokens,
                threads = throttle.threads,
                useGpu = false
            )
        }
        return result
    }

    suspend fun unload() = withContext(dispatchers.io) {
        engineMutex.withLock {
            LocalEngineLoader.unload()
            _loadState.value = LocalLoadState.Idle
        }
    }

    /** Benchmark: tokens/sec on this device; loads + unloads around the run. */
    suspend fun benchmark(modelId: String): Result<Double> = withContext(dispatchers.io) {
        engineMutex.withLock {
            val record = _models.value.firstOrNull { it.id == modelId }
                ?: return@withLock Result.failure(IllegalArgumentException("Unknown local model"))
            val previous = _loadState.value
            _loadState.value = LocalLoadState.Loading(modelId, record.displayName)
            val throttle = throttleDecision()
            val result = LocalEngineLoader.benchmarkTokensPerSec(
                path = File(modelsDir, record.fileName).absolutePath,
                contextTokens = LocalModelRouter.effectiveContextTokens(record.contextLength),
                threads = throttle.threads,
                prompt = "Write a short story about a robot who learns to paint.",
                maxTokens = 128,
                // Benchmark measures the config the user will actually run.
                useGpu = throttle.allowGpu
            )
            // Restore whatever was active before the benchmark (its OWN file).
            _loadState.value = previous
            if (previous is LocalLoadState.Ready) {
                val activeRecord = _models.value.firstOrNull { it.id == previous.modelId }
                if (activeRecord != null) {
                    loadEngine(activeRecord)
                }
            }
            result
        }
    }

    // ---- Storage -----------------------------------------------------------

    /** Total bytes used by local model files on disk. */
    fun storageUsedBytes(): Long = _models.value.sumOf { record ->
        File(modelsDir, record.fileName).takeIf { it.exists() }?.length() ?: 0L
    }

    suspend fun delete(modelId: String) = withContext(dispatchers.io) {
        val record = _models.value.firstOrNull { it.id == modelId }
        if (record != null) {
            val active = _loadState.value
            if (active is LocalLoadState.Ready && active.modelId == modelId) {
                unload()
            }
            File(modelsDir, record.fileName).delete()
            _models.value = _models.value.filterNot { it.id == modelId }
            persist()
        }
        Unit
    }

    // ---- Device fit --------------------------------------------------------

    /** (totalRamBytes, freeRamBytes) for the "fits your device" indicator. */
    fun deviceMemory(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 4L * 1024 * 1024 * 1024 to 2L * 1024 * 1024 * 1024
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem to memoryInfo.availMem
    }

    private fun defaultThreads(): Int =
        // Conservative default for phone SoCs: leave headroom for the system
        // instead of pinning every core (thermal discipline, milestone 7).
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /** Test hook: replace the manifest contents (never used in prod paths). */
    fun replaceAllForTest(records: List<LocalModelRecord>) {
        _models.value = records
        persist()
    }
}
