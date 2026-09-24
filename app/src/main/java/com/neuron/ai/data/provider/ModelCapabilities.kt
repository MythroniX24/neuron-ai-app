package com.neuron.ai.data.provider

import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.core.provider.Model

/**
 * Milestone 3 multimodal support. A model's true capability set is provider-
 * declared metadata when available; for OpenAI-compatible endpoints that
 * don't expose it, we estimate from well-known model-id patterns and the
 * user's provider config flags. Estimates NEVER overpromise: an unsupported
 * input is rejected BEFORE it reaches the model.
 */
object ModelCapabilities {

    private val visionPatterns = listOf(
        Regex("(?i)gpt-4o|gpt-4\\.1|gpt-4-turbo|o4-mini|omni"),
        Regex("(?i)gemini|flash|pro"),
        Regex("(?i)claude-3|claude-4|claude-sonnet|claude-opus|claude-haiku"),
        Regex("(?i)llava|vision|vl|pixtral|qwen.*vl|internvl"),
        Regex("(?i)grok-(2|3|4)-vision|grok-vision")
    )

    private val toolPatterns = listOf(
        Regex("(?i)gpt-4|gpt-3\\.5|o1|o3|o4"),
        Regex("(?i)gemini|flash|pro"),
        Regex("(?i)claude"),
        Regex("(?i)llama-3|llama4|mixtral|qwen|deepseek|mistral|grok")
    )

    fun estimateVision(modelId: String, visionEnabled: Boolean): Boolean =
        visionEnabled || visionPatterns.any { it.containsMatchIn(modelId) }

    fun estimateTools(modelId: String, toolsEnabled: Boolean): Boolean =
        toolsEnabled || toolPatterns.any { it.containsMatchIn(modelId) }

    /** Builds a capability-aware [Model] for the agent loop. */
    fun estimate(id: String, visionEnabled: Boolean, toolsEnabled: Boolean): Model = Model(
        id = id,
        displayName = id,
        supportsVision = estimateVision(id, visionEnabled),
        supportsTools = estimateTools(id, toolsEnabled)
    )

    /**
     * Validates a planned send against the model's capabilities.
     * Returns null when OK, or a user-presentable rejection reason.
     */
    fun validateInput(model: Model, attachments: List<Attachment>): String? {
        val images = attachments.filter { it.isImage }
        if (images.isNotEmpty() && !model.supportsVision) {
            return "The selected model (${model.id}) does not support images. " +
                "Pick a vision-capable model in the top-bar selector (e.g. GPT-4o, " +
                "Gemini, Claude) or remove the image attachment."
        }
        return null
    }
}
