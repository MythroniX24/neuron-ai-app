package com.neuron.ai.data.local

/**
 * Curated recommended local models — a hand-picked CATALOG (never a live
 * search) so the Local AI section isn't empty on first use. The catalog only
 * names a repo and a quant PREFERENCE; the actual file names are resolved
 * live from the Hub listing, because repos get renamed upstream and a
 * hardcoded name 404s silently. Ordered roughly by speed-vs-quality for
 * phone hardware; each entry carries explicit minimum-RAM guidance so the UI
 * can warn or grey out what this device won't run well.
 */
data class RecommendedModel(
    val id: String,
    val displayName: String,
    val hfRepo: String,
    /** Rough downloaded size of the recommended default quantization, GB. */
    val approxSizeGb: Double,
    /** Minimum total device RAM for acceptable performance, GB. */
    val minRamGb: Double,
    /** Minimum FREE memory headroom needed while running, GB. */
    val minFreeRamGb: Double,
    val notes: String,
    /** true when this entry is a "needs more RAM" quality pick. */
    val needsMoreRam: Boolean = false,
    /**
     * Which quantization to PREFER when the repo offers several (Q4_K_M first
     * — the phone sweet spot). NEVER a hardcoded file name: the exact file is
     * resolved from the live listing (see HfHubClient.selectVariant).
     */
    val preferredQuants: List<String> = HfHubClient.DEFAULT_QUANT_PREFERENCE,
    /** Repo listing resolved at runtime — filled by the view model. */
    val variants: List<HfHubClient.SearchResult.Variant> = emptyList(),
    /** Why this entry cannot be downloaded right now (gated/missing/...). */
    val unavailableReason: String? = null
) {
    /** true once the live listing came back with at least one GGUF file. */
    val downloadable: Boolean get() = variants.isNotEmpty()

    /** Speed-vs-quality ordering used by the Recommended section. */
    val speedTier: Int
        get() = when (id) {
            "tinyllama" -> 0
            "llama-3.2-1b", "qwen25-1_5b" -> 1
            "gemma-2-2b", "qwen25-3b", "llama-3.2-3b" -> 2
            "phi-3-mini" -> 3
            "gemma-2-9b" -> 4
            else -> 2
        }
}

object RecommendedModels {

    val all: List<RecommendedModel> = listOf(
        RecommendedModel(
            id = "tinyllama",
            displayName = "TinyLlama 1.1B",
            hfRepo = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            approxSizeGb = 0.7,
            minRamGb = 2.0,
            minFreeRamGb = 1.2,
            notes = "Fastest, lowest quality — good for weak devices",
            needsMoreRam = false
        ),
        RecommendedModel(
            id = "llama-3.2-1b",
            displayName = "Llama 3.2 1B",
            hfRepo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            approxSizeGb = 0.8,
            minRamGb = 2.5,
            minFreeRamGb = 1.5,
            notes = "Very fast, decent quality for its size"
        ),
        RecommendedModel(
            id = "qwen25-1_5b",
            displayName = "Qwen2.5 1.5B",
            hfRepo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            approxSizeGb = 1.1,
            minRamGb = 3.0,
            minFreeRamGb = 1.8,
            notes = "Strong for its size, good multilingual"
        ),
        RecommendedModel(
            id = "gemma-2-2b",
            displayName = "Gemma 2 2B",
            hfRepo = "bartowski/gemma-2-2b-it-GGUF",
            approxSizeGb = 1.6,
            minRamGb = 3.0,
            minFreeRamGb = 2.0,
            notes = "Great quality-per-GB, concise answers"
        ),
        RecommendedModel(
            id = "qwen25-3b",
            displayName = "Qwen2.5 3B",
            hfRepo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            approxSizeGb = 2.0,
            minRamGb = 4.0,
            minFreeRamGb = 2.5,
            notes = "Noticeably better reasoning than 1B class"
        ),
        RecommendedModel(
            id = "llama-3.2-3b",
            displayName = "Llama 3.2 3B",
            hfRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            approxSizeGb = 2.0,
            minRamGb = 4.0,
            minFreeRamGb = 2.5,
            notes = "Balanced speed and quality"
        ),
        RecommendedModel(
            id = "phi-3-mini",
            displayName = "Phi-3 Mini",
            hfRepo = "microsoft/Phi-3-mini-4k-instruct-gguf",
            approxSizeGb = 2.3,
            minRamGb = 4.0,
            minFreeRamGb = 2.8,
            notes = "Excellent reasoning for its size"
        ),
        RecommendedModel(
            id = "gemma-2-9b",
            displayName = "Gemma 2 9B",
            hfRepo = "bartowski/gemma-2-9b-it-GGUF",
            approxSizeGb = 5.5,
            minRamGb = 8.0,
            minFreeRamGb = 6.0,
            notes = "Highest quality here — needs more RAM",
            needsMoreRam = true
        )
    ).sortedBy { it.speedTier }

    /** Device fit verdict for the UI (grey-out vs warn vs fine). */
    enum class Fit { FINE, TIGHT, UNLIKELY }

    /**
     * Classifies a model against this device's memory. Conservative:
     * [totalRamBytes] is what ActivityManager reports, already less than
     * physical RAM on most devices.
     */
    fun fitForDevice(model: RecommendedModel, totalRamBytes: Long, freeRamBytes: Long): Fit {
        val totalGb = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGb = freeRamBytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            totalGb < model.minRamGb || freeGb < model.minFreeRamGb * 0.6 -> Fit.UNLIKELY
            freeGb < model.minFreeRamGb -> Fit.TIGHT
            else -> Fit.FINE
        }
    }
}
