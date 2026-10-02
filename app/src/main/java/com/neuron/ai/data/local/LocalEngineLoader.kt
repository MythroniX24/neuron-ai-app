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
        errOut: Array<String?>
    ): Int
    private external fun nativeUnload(): Boolean
    private external fun nativeChatTemplate(): String
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        callback: TokenCallback
    ): Int

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
    fun load(path: String, contextTokens: Int, threads: Int, useGpu: Boolean): LoadResult {
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
        val code = nativeLoad(path, contextTokens, threads, useGpu, err)
        return when {
            code == 0 -> LoadResult.Success
            // New: GPU offload was requested but no Vulkan driver is present.
            code == -6 -> LoadResult.Failure(
                err[0] ?: "No Vulkan GPU driver is available on this device"
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
        var failure: String? = null
        val callback = TokenCallback { piece ->
            try {
                onToken(piece)
            } catch (t: Throwable) {
                failure = t.message ?: "callback failed"
            }
        }
        return when (val code = nativeGenerate(prompt, maxTokens, callback)) {
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
