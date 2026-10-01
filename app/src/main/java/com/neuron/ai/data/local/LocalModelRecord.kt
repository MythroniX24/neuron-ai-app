package com.neuron.ai.data.local

import kotlinx.serialization.Serializable

/**
 * One downloaded/imported on-device model. Persisted as JSON in app-private
 * storage; the model FILE lives workspace-isolated in the designated
 * local-models directory — nothing outside that directory is ever touched.
 */
@Serializable
data class LocalModelRecord(
    /** Stable selector id (e.g. "local-llama-3-2-1b"); prefixed "local-". */
    val id: String,
    val displayName: String,
    /** File name inside the models directory. */
    val fileName: String,
    val sizeBytes: Long,
    val quantization: String? = null,
    val architecture: String? = null,
    /** From GGUF metadata; drives TokenBudgetManager like any cloud model. */
    val contextLength: Long? = null,
    val blockCount: Long? = null,
    /** "import" or the HF repo id it was downloaded from. */
    val source: String = SOURCE_IMPORT,
    val importedAtEpochMs: Long = 0L,
    /** "Enable for chat" tick — ONLY ticked models appear in the switcher. */
    val enabledForChat: Boolean = false
) {
    companion object {
        const val SOURCE_IMPORT = "import"
        const val SOURCE_DOWNLOAD = "download"
    }
}

/** Single-active load lifecycle shared by the switcher and Settings. */
sealed class LocalLoadState {
    data object Idle : LocalLoadState()
    data class Loading(val modelId: String, val displayName: String) : LocalLoadState()
    data class Ready(val modelId: String) : LocalLoadState()
    data class Failed(val modelId: String, val reason: String) : LocalLoadState()
}
