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

    // ---- Per-model routing rules (milestone 9) -----------------------------
    // Declared BEFORE the init block on purpose: Kotlin runs initializers in
    // declaration order, so loading the rules inside init{} would touch
    // uninitialized fields.

    private val routingRulesFile = File(context.filesDir, "local-routing-rules.json")

    private val _routingRules = MutableStateFlow<List<ModelRoutingRule>>(emptyList())

    /**
     * User-defined "use this model for these kinds of turn" rules, in priority
     * order. Empty by default — with no rules the milestone-6 capability
     * fallback applies unchanged.
     */
    var routingRules: List<ModelRoutingRule>
        get() = _routingRules.value
        private set(value) {
            _routingRules.value = value
            try {
                routingRulesFile.writeText(json.encodeToString(value))
            } catch (t: Throwable) {
                logger?.w("LocalModels", "Routing rules persist failed", t)
            }
        }

    private fun loadRoutingRules() {
        _routingRules.value = try {
            if (routingRulesFile.exists()) {
                json.decodeFromString<List<ModelRoutingRule>>(routingRulesFile.readText())
            } else {
                emptyList()
            }
        } catch (t: Throwable) {
            logger?.w("LocalModels", "Routing rules unreadable — ignoring", t)
            emptyList()
        }
    }

    init {
        loadManifest()
        loadRoutingRules()
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

    /**
     * Adopts GGUF files that sit in the models directory but are missing from
     * the manifest, and returns how many were adopted.
     *
     * The download flow renames the finished `.part` into the models directory
     * BEFORE it registers the record, so any failure between those two steps
     * (a validation bug, a crash, the process being killed) leaves a
     * gigabyte-scale file on disk that the UI cannot see — and the only way
     * out was to download it all again. This makes the directory the source
     * of truth on startup.
     *
     * Vision projectors (`*-mmproj*.gguf`) are deliberately skipped: they are
     * companions of a model, not models.
     */
    suspend fun adoptOrphanedFiles(): Int = withContext(dispatchers.io) {
        val known = _models.value.mapNotNullTo(mutableSetOf()) { it.fileName }
        val orphans = (modelsDir.listFiles() ?: return@withContext 0)
            .filter { candidate ->
                candidate.isFile &&
                    candidate.name.endsWith(".gguf", ignoreCase = true) &&
                    candidate.name !in known &&
                    !VisionProjector.isProjectorFile(candidate.name)
            }
        var adopted = 0
        for (orphan in orphans) {
            val display = HfHubClient.quantOf(orphan.name)?.let { quant ->
                "${orphan.nameWithoutExtension} ($quant)"
            } ?: orphan.nameWithoutExtension
            registerExistingFile(
                orphan,
                displayName = display,
                source = LocalModelRecord.SOURCE_DOWNLOAD
            ).onSuccess { adopted++ }
                .onFailure { t ->
                    logger?.w("LocalModels", "Orphan ${orphan.name} not adopted: ${t.message}")
                }
        }
        if (adopted > 0) logger?.d("LocalModels", "Adopted $adopted orphaned model file(s)")
        adopted
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
                    // The GGUF's own quantization string wins; otherwise fall
                    // back to the file name, which is what every Hub quant
                    // release actually uses (general.file_type is ambiguous —
                    // see GgufReader).
                    quantization = info.quantization ?: HfHubClient.quantOf(sourceFile.name),
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
                quantization = info.quantization ?: HfHubClient.quantOf(file.name),
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

    // ---- Vision projector (milestone 9) ------------------------------------

    /**
     * The mmproj file belonging to [record], or null when it has none. Pure
     * matching (VisionProjector) against the models directory — a projector is
     * only "present" when it really was imported next to the model.
     */
    fun visionProjectorFor(record: LocalModelRecord): File? {
        val names = try {
            modelsDir.listFiles()?.map { it.name }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val match = VisionProjector.findProjectorFor(record.fileName, names) ?: return null
        return File(modelsDir, match).takeIf { it.exists() && it.length() > 0L }
    }

    /** true when [record] can genuinely read an image (tower + projector). */
    fun hasVisionProjector(record: LocalModelRecord): Boolean {
        if (!LocalModelRouter.architectureSupportsVision(record.architecture)) return false
        return visionProjectorFor(record) != null
    }

    // ---- Per-model routing rules (milestone 9) -----------------------------

    /** Turns one routing axis on/off for [modelId]; order is kept stable. */
    fun setRoutingRule(modelId: String, kind: LocalTurnKind, enabled: Boolean) {
        val current = LocalRoutingRules.ruleFor(routingRules, modelId).with(kind, enabled)
        val others = routingRules.filterNot { it.modelId == modelId }
        val updated = if (current.text || current.tools || current.vision) {
            others + current
        } else {
            // Fully off: drop the rule instead of storing a dead one.
            others
        }
        routingRules = updated
    }

    /** The rule for [modelId] as the UI should show it (defaults to inert). */
    fun routingRuleFor(modelId: String): ModelRoutingRule =
        LocalRoutingRules.ruleFor(routingRules, modelId)

    /**
     * Routes one turn to a local model by the user's rules, falling back to a
     * capability-based pick. null = nothing on-device can serve this turn.
     */
    fun pickRoutedModel(kind: LocalTurnKind): LocalModelRecord? =
        LocalRoutingRules.pick(kind, routingRules, _models.value) { hasVisionProjector(it) }

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
                    // Include the llama.cpp log so Settings → Debug console can show
                    // exactly what ggml said (e.g. "Unsupported device" on PowerVR).
                    val log = runCatching { LocalEngineLoader.diagnostics(modelPath = File(modelsDir, record.fileName).absolutePath).logText }
                        .getOrNull()
                        ?.trimEnd()
                    val reason = if (log.isNotBlank()) "${result.reason}\n\nllama.cpp log:\n${log}" else result.reason
                    LocalLoadState.Failed(modelId, reason)
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
        // Mmapping a multi-GB GGUF takes real time, and the user will very
        // often switch apps mid-load. Without this the process can be killed
        // and the whole load restarts from zero when they come back.
        ModelTransferService.ensureRunning(context)
        ModelTransferService.setLoading(record.displayName, true)
        return try {
            loadEngineInternal(record)
        } finally {
            ModelTransferService.setLoading(record.displayName, false)
        }
    }

    private fun loadEngineInternal(record: LocalModelRecord): LocalEngineLoader.LoadResult {
        val path = File(modelsDir, record.fileName).absolutePath
        // Milestone 6: the GGUF's declared context, capped to what a phone can
        // hold. Milestone 7: threads + GPU filtered through the throttle policy.
        val contextTokens = LocalModelRouter.effectiveContextTokens(record.contextLength)
        val throttle = throttleDecision()
        val wantGpu = throttle.allowGpu
        // Milestone 9: attach the projector when one was imported beside the
        // model — the only way the model can actually read an image.
        val mmproj = visionProjectorFor(record)?.absolutePath
        var result = LocalEngineLoader.load(
            path = path,
            contextTokens = contextTokens,
            threads = throttle.threads,
            useGpu = wantGpu,
            mmprojPath = mmproj
        )
        if (result is LocalEngineLoader.LoadResult.Failure && wantGpu) {
            logger?.w("LocalModels", "GPU load failed — retrying on CPU: ${result.reason}")
            LocalEngineLoader.unload()
            result = LocalEngineLoader.load(
                path = path,
                contextTokens = contextTokens,
                threads = throttle.threads,
                useGpu = false,
                mmprojPath = mmproj
            )
        }
        // A projector that refuses to load must not take the whole model down:
        // reload text-only so the user keeps a working model.
        if (result is LocalEngineLoader.LoadResult.Failure && mmproj != null) {
            logger?.w("LocalModels", "Projector failed — reloading text-only: ${result.reason}")
            LocalEngineLoader.unload()
            result = LocalEngineLoader.load(
                path = path,
                contextTokens = contextTokens,
                threads = throttle.threads,
                useGpu = wantGpu,
                mmprojPath = null
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

    private fun defaultThreads(): Int {
        // Milestone 8: thread the BIG cores, not every core. A phone SoC
        // mixes 2-4 fast cores with a much slower efficiency cluster; running
        // inference on the little ones costs 2-4x per clock and starves the
        // big cores. Falls back to the old conservative cap when topology
        // can't be read.
        val total = Runtime.getRuntime().availableProcessors()
        return try {
            val bigCores = CpuTopology.bigCoreCount(CpuTopology.readPeakFrequencies(total))
            CpuTopology.resolveThreads(bigCores = bigCores, totalCores = total)
        } catch (_: Throwable) {
            total.coerceIn(2, 6)
        }
    }

    /** Test hook: replace the manifest contents (never used in prod paths). */
    fun replaceAllForTest(records: List<LocalModelRecord>) {
        _models.value = records
        persist()
    }
}
