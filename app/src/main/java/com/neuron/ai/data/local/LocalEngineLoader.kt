package com.neuron.ai.data.local

/**
 * Kotlin side of the llama.cpp JNI bridge. Owns the SINGLE-ACTIVE-MODEL
 * constraint at this layer too: loading a model fully unloads the previous
 * one (never two large models resident at once), and load failures keep the
 * previous state consistent.
 *
 * The native library may be absent (stub build) or load may fail (corrupt
 * file, OOM) — every failure is reported with the actual reason and leaves
 * no half-loaded state behind.
 */
object LocalEngineLoader {

    private const val LIB_NAME = "neuron_llama"

    /** Present once System.loadLibrary succeeded; false on stub builds. */
    val nativeLibraryAvailable: Boolean by lazy { try {
        System.loadLibrary(LIB_NAME)
        true
    } catch (_: Throwable) {
        false
    } }

    /**
     * Vulkan GPU acceleration usable in THIS build AND on THIS device.
     * false on stub builds, CPU-only builds, emulators and driverless devices
     * — the caller then never requests GPU offload.
     */
    val gpuAvailable: Boolean
        get() = nativeLibraryAvailable && try { nativeGpuAvailable() } catch (_: Throwable) { false }

    /**
     * Directory ggml searches for RUNTIME backends (the app's native library
     * dir, holding libggml-cpu-*.so variants and libggml-vulkan.so). Set once
     * at app startup; actual dlopen happens lazily inside [load] so heavy
     * work never runs on the main thread.
     */
    @Volatile
    private var backendDir: String? = null

    fun setBackendDir(dir: String?) {
        backendDir = dir
    }

    /** Informational; from llama.cpp when built, "unavailable" otherwise. */
    external fun nativeVersion(): String

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeGpuAvailable(): Boolean
    private external fun nativeInitBackends(dir: String)
    private external fun nativeLoad(
        path: String,
        contextTokens: Int,
        threads: Int,
        useGpu: Boolean,
        mmprojPath: String?,
        errOut: Array<String?>
    ): Int
    private external fun nativeUnload(): Boolean
    private external fun nativeChatTemplate(): String
    private external fun nativeTokenize(prompt: String): IntArray
    private external fun nativeCachedTokens(): IntArray
    private external fun nativeGenerateTokens(
        tokens: IntArray,
        maxTokens: Int,
        reusePrefix: Int,
        callback: TokenCallback
    ): Int
    private external fun nativeVisionAvailable(): Boolean
    private external fun nativeGenerateMultimodal(
        prompt: String,
        image: ByteArray,
        maxTokens: Int,
        callback: TokenCallback
    ): Int

    /**
     * Milestone 9: TRUE when the ACTIVE model has a vision projector attached,
     * i.e. it can genuinely read an image — the honest signal the router uses
     * instead of guessing from the architecture name.
     */
    val visionActive: Boolean
        get() = nativeLibraryAvailable && try {
            nativeVisionAvailable()
        } catch (_: Throwable) {
            false
        }

    /** Last turn's prompt-prefix reuse, for the performance card. */
    @Volatile
    var lastPromptTokens: Int = 0
        private set

    @Volatile
    var lastReusedTokens: Int = 0
        private set

    /** Streaming callback invoked from the native decode loop (one thread). */
    fun interface TokenCallback {
        fun text(piece: String)
    }

    sealed class LoadResult {
        data object Success : LoadResult()
        data class Failure(val reason: String) : LoadResult()
    }

    sealed class GenerationResult {
        data class Done(val tokensGenerated: Int) : GenerationResult()
        data class Error(val message: String) : GenerationResult()
    }

    /**
     * Loads a model (mmap). ALWAYS unloads any previously active model first —
     * the single-active constraint, enforced here so callers can't violate it.
     * Returns [LoadResult.Failure] with the native error text on failure; the
     * engine is left UNLOADED in that case (never half-loaded).
     */
    fun load(
        path: String,
        contextTokens: Int,
        threads: Int,
        useGpu: Boolean,
        /** Vision projector (mmproj) to attach; null = text-only model. */
        mmprojPath: String? = null
    ): LoadResult {
        if (!nativeLibraryAvailable) {
            return LoadResult.Failure(
                "On-device inference engine is not available in this build."
            )
        }
        unload()
        if (!nativeIsAvailable()) {
            return LoadResult.Failure(
                "llama.cpp was not compiled into this APK (native fetch failed at build time)."
            )
        }
        // Runtime backend discovery (per-ISA CPU variants + Vulkan), a no-op
        // after the first call.
        backendDir?.let { dir -> runCatching { nativeInitBackends(dir) } }
        val err = arrayOfNulls<String>(1)
        val code = nativeLoad(path, contextTokens, threads, useGpu, mmprojPath, err)
        return when {
            code == 0 -> LoadResult.Success
            // New: GPU offload was requested but no Vulkan driver is present.
            code == -6 -> LoadResult.Failure(
                err[0] ?: "No Vulkan GPU driver is available on this device"
            )
            // Milestone 9: the projector the user imported doesn't match.
            code == -9 -> LoadResult.Failure(
                err[0] ?: "Vision projector (mmproj) could not be loaded"
            )
            else -> LoadResult.Failure(err[0] ?: "Engine load failed (code $code)")
        }
    }

    /** Fully unloads the active model (no-op when nothing is loaded). */
    fun unload() {
        if (nativeLibraryAvailable) nativeUnload()
    }

    fun loadedChatTemplate(): String =
        if (nativeLibraryAvailable) nativeChatTemplate() else ""

    /**
     * Streams generation. [prompt] must be chat-template-rendered by the
     * caller. Thread-safe against load/unload via the native mutex; the
     * callback fires on the CALLING thread while the native lock is held —
     * [onToken] must not re-enter [load]/[unload].
     */
    fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): GenerationResult {
        if (!nativeLibraryAvailable) {
            return GenerationResult.Error("Inference engine is not available in this build.")
        }
        // Milestone 8: tokenize once, reuse the cached prefix. A native
        // failure here falls back to "no reuse", never to an error.
        val tokens = runCatching { nativeTokenize(prompt) }.getOrElse { IntArray(0) }
        val reuse = if (tokens.isEmpty()) {
            0
        } else {
            PromptPrefix.commonPrefixLength(
                runCatching { nativeCachedTokens() }.getOrElse { IntArray(0) },
                tokens
            )
        }
        lastPromptTokens = tokens.size
        lastReusedTokens = reuse

        var failure: String? = null
        val callback = TokenCallback { piece ->
            try {
                onToken(piece)
            } catch (t: Throwable) {
                failure = t.message ?: "callback failed"
            }
        }
        if (tokens.isEmpty()) {
            return GenerationResult.Error("Could not tokenize the conversation")
        }
        return when (val code = nativeGenerateTokens(tokens, maxTokens, reuse, callback)) {
            0 -> GenerationResult.Done(0) // nothing generated (immediate EOS)
            in 1..Int.MAX_VALUE -> GenerationResult.Done(code)
            -100 -> GenerationResult.Error("Engine unavailable (stub build)")
            -3 -> GenerationResult.Error("No model is loaded")
            -4 -> GenerationResult.Error("Could not tokenize the conversation")
            -5 -> GenerationResult.Error("Prompt exceeded the model's context window")
            else -> GenerationResult.Error(
                failure ?: "Generation failed (code $code)"
            )
        }
    }

    /**
     * Milestone 9: streams an answer about an IMAGE. [prompt] must contain the
     * model's media marker (see [MEDIA_MARKER]); the projector turns [image]
     * into embeddings the text model reads. No prefix reuse here — the cache
     * now holds embeddings, not comparable token ids.
     */
    fun generateMultimodalStreaming(
        prompt: String,
        image: ByteArray,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): GenerationResult {
        if (!nativeLibraryAvailable) {
            return GenerationResult.Error("Inference engine is not available in this build.")
        }
        if (!visionActive) {
            return GenerationResult.Error(
                "This model has no vision projector loaded — import its mmproj file first."
            )
        }
        var failure: String? = null
        val callback = TokenCallback { piece ->
            try {
                onToken(piece)
            } catch (t: Throwable) {
                failure = t.message ?: "callback failed"
            }
        }
        val code = try {
            nativeGenerateMultimodal(prompt, image, maxTokens, callback)
        } catch (t: Throwable) {
            return GenerationResult.Error(t.message ?: "Vision decode failed")
        }
        // An image turn leaves embeddings in the cache: no token-prefix reuse.
        lastPromptTokens = 0
        lastReusedTokens = 0
        return when {
            code == 0 -> GenerationResult.Done(0)
            code > 0 -> GenerationResult.Done(code)
            code == -6 -> GenerationResult.Error(
                "This model has no vision projector loaded."
            )
            code == -9 -> GenerationResult.Error(
                "The image could not be decoded by this model's projector."
            )
            code == -3 -> GenerationResult.Error("No model is loaded")
            else -> GenerationResult.Error(failure ?: "Vision decode failed (code $code)")
        }
    }

    /** llama.cpp's default media marker; must appear once per image. */
    const val MEDIA_MARKER = "<|image|>"

    /**
     * Runs a short benchmark: tokens generated per second on this device.
     * Loads [path] first (unloading whatever is active) and restores the
     * caller to an unloaded state afterwards.
     */
    fun benchmarkTokensPerSec(
        path: String,
        contextTokens: Int,
        threads: Int,
        prompt: String,
        maxTokens: Int,
        useGpu: Boolean = false
    ): Result<Double> {
        val load = load(path, contextTokens, threads, useGpu)
        if (load is LoadResult.Failure) return Result.failure(IllegalStateException(load.reason))
        return try {
            val start = System.nanoTime()
            var tokens = 0
            val result = generateStreaming(prompt, maxTokens) { tokens++ }
            if (result is GenerationResult.Error) {
                Result.failure(IllegalStateException(result.message))
            } else {
                val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                if (seconds <= 0.0 || tokens == 0) {
                    Result.failure(IllegalStateException("Benchmark produced no tokens"))
                } else {
                    Result.success(tokens / seconds)
                }
            }
        } finally {
            unload()
        }
    }

    // ---- Convenience ------------------------------------------------------------------

    fun engineVersion(): String =
        if (nativeLibraryAvailable) nativeVersion() else "unavailable"
}
