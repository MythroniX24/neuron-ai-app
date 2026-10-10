package com.neuron.ai.data.local

import kotlin.math.max

/**
 * How much FREE memory a model load actually needs.
 *
 * Why this is a check and not a hope: when the numbers do not fit, the
 * low-memory killer ends the process in the middle of the load. Nothing is
 * thrown, nothing is logged, no dialog appears — to the user the app simply
 * "crashes when I load a model". A pre-flight refusal turns that silent death
 * into a sentence they can act on (close apps / smaller quant / smaller
 * model) and keeps the app alive to say it.
 *
 * The budget is deliberately the SAME shape as the guidance already shown in
 * Settings → Local AI ([RecommendedModels.minFreeRamGb]), so a model the UI
 * labels as fitting this device is never refused here:
 *
 *   required = model file + max(400 MB, contextTokens x layers x 2 KB)
 *
 * The second term is the KV cache plus llama.cpp's compute buffers; 2 KB per
 * token per layer is a generous stand-in for an 8-head x 256-dim KV at Q8_0
 * (the exact cost depends on the model's own head geometry, which is not worth
 * re-deriving on a phone).
 *
 * Pure: no Android, no JNI — so the arithmetic is unit-tested rather than
 * trusted.
 */
object LoadMemoryBudget {

    /**
     * Floor for the KV cache + compute buffers + runtime noise. Sized so the
     * budget stays inside the curated catalog's own guidance for EVERY entry
     * (see RecommendedModels.minFreeRamGb): the tightest one is Gemma 2 2B,
     * a 1.6 GB file with 2.0 GB of recommended free RAM.
     */
    const val SCRATCH_FLOOR_BYTES: Long = 400L * 1024 * 1024

    /** Generous per-token-per-layer KV cost (see the class comment). */
    const val KV_BYTES_PER_TOKEN_PER_LAYER: Long = 2048

    /** KV + compute terms, floored so a tiny/short-context model still has room. */
    fun kvAndScratchBytes(contextTokens: Int, layerCount: Long?): Long {
        val tokens = contextTokens.toLong().coerceAtLeast(0L)
        val layers = (layerCount ?: 0L).coerceAtLeast(0L)
        return max(SCRATCH_FLOOR_BYTES, tokens * layers * KV_BYTES_PER_TOKEN_PER_LAYER)
    }

    /** Free bytes required before [modelFileBytes] may be loaded. */
    fun requiredFreeBytes(
        modelFileBytes: Long,
        contextTokens: Int,
        layerCount: Long?
    ): Long = modelFileBytes.coerceAtLeast(0L) + kvAndScratchBytes(contextTokens, layerCount)

    /** true when [availBytes] covers [requiredBytes]. */
    fun fits(requiredBytes: Long, availBytes: Long): Boolean = availBytes >= requiredBytes

    /** The message the user gets instead of a process kill. */
    fun describeShortfall(requiredBytes: Long, availBytes: Long): String =
        "Not enough free memory to load this model safely. It needs about " +
            "${gb(requiredBytes)} free and this device has ${gb(availBytes)} " +
            "available right now. Close other apps and try again, or pick a " +
            "smaller quantization / model."

    private fun gb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
