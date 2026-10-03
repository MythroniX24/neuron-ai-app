package com.neuron.ai.data.local

/**
 * Milestone 9 — vision projector (mmproj) discovery, the pure half.
 *
 * llama.cpp keeps the image encoder in a SEPARATE GGUF: the text model says
 * "I am a vision model" in its architecture, but it cannot actually see a
 * picture without its projector file. Claiming vision from the architecture
 * name alone is exactly the kind of overpromising milestone 6 was built to
 * avoid, so vision is only reported when a real projector file is present next
 * to the model.
 *
 * Pure: the caller passes the file names, this decides. Unit-tested.
 */
object VisionProjector {

    private const val MARKER = "mmproj"

    /**
     * Picks the projector belonging to [modelFileName] out of [availableFiles],
     * or null when the model has none (a text-only model, or a projector that
     * was never imported).
     *
     * Preference order:
     *  1. "<modelBase>-mmproj*.gguf" — the Hugging Face naming convention
     *     (e.g. Qwen2.5-VL-3B-Instruct-f16.gguf + Qwen2.5-VL-3B-Instruct-mmproj-f16.gguf)
     *  2. "mmproj-*.gguf" — the llama.cpp default naming
     *  3. any other file whose name contains "mmproj"
     *
     * Split shards and the model file itself are never returned.
     */
    fun findProjectorFor(modelFileName: String, availableFiles: List<String>): String? {
        val base = baseName(modelFileName)
        val candidates = availableFiles.filter { it != modelFileName }.filter { candidate ->
            val lower = candidate.lowercase()
            lower.endsWith(".gguf") && lower.contains(MARKER) && !isShard(candidate)
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.lowercase().startsWith("$base-$MARKER") }
            ?: candidates.firstOrNull { it.lowercase().startsWith(MARKER) }
            ?: candidates.minByOrNull { it.length }
    }

    /** true when [modelFileName] has a usable projector beside it. */
    fun hasProjector(modelFileName: String, availableFiles: List<String>): Boolean =
        findProjectorFor(modelFileName, availableFiles) != null

    /**
     * true when the projector itself declares image support. Kept separate
     * from the file name so the native check (mtmd_support_vision) stays the
     * final authority — this is only a cheap pre-filter.
     */
    fun isProjectorFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".gguf") && lower.contains(MARKER) && !isShard(fileName)
    }

    private fun isShard(fileName: String): Boolean =
        Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)

    /**
     * The model stem without its quant suffix: "Qwen2.5-VL-3B-Instruct-Q4_K_M"
     * → "qwen2.5-vl-3b-instruct". The suffix has to come off, otherwise the
     * "<model>-mmproj" match below can never fire and we would fall through to
     * whichever unrelated "mmproj-*" file happens to be in the folder.
     *
     * The quant token itself contains underscores (Q4_K_M, IQ4_XS), so the
     * class is `[a-z0-9_]+` — plain `[a-z0-9]+` stops at the first underscore
     * and silently fails to strip anything.
     */
    private val QUANT_TAIL =
        Regex("""-(?:i?q\d+_[a-z0-9_]+|f\d+|bf16)$""")

    private fun baseName(fileName: String): String =
        QUANT_TAIL.replace(fileName.substringBeforeLast('.').lowercase(), "")
}
