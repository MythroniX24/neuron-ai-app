package com.neuron.ai.data.local

/**
 * Milestone 6 — capability-aware ON-DEVICE ROUTING.
 *
 * The on-device model is just another [com.neuron.ai.core.provider.AIProvider],
 * but its capabilities are DECLARED BY THE MODEL FILE, not by a provider
 * flag: a GGUF either ships a tool-calling chat template or it does not, and
 * its context window is whatever its metadata says (capped at what a phone
 * can actually hold in RAM).
 *
 * Everything here is PURE: no Android, no JNI, no coroutines — so routing
 * decisions are trivially unit-testable and identical on every device.
 *
 * Design rules:
 * - NEVER overpromise. A capability we cannot prove stays OFF, and the
 *   degradation is surfaced to the user (timeline step) instead of being
 *   silently swallowed.
 * - A capability we CANNOT serve at all (images on a text-only GGUF) is a
 *   REFUSAL with an actionable reason, never a broken send.
 */

/** What ONE local model can actually do, derived from its own GGUF metadata. */
data class LocalModelCapabilities(
    val modelId: String,
    val displayName: String,
    /** Model ships a tool-calling chat template AND the turn may use tools. */
    val supportsTools: Boolean,
    /** Model architecture declares a vision tower (mmproj-style GGUF). */
    val supportsVision: Boolean,
    /** Context window actually used, clamped to [LocalModelRouter.MAX_CONTEXT_TOKENS]. */
    val contextWindowTokens: Int,
    /** Raw context length declared by the GGUF (null when undeclared). */
    val declaredContextTokens: Int?,
    /** Human-readable capability notes — surfaced verbatim in the timeline. */
    val notes: List<String>
)

/** What the CURRENT turn needs from the model. */
data class LocalRouteRequest(
    val hasImageAttachments: Boolean = false,
    /** Workspace/Terminal/Browser are enabled for this chat → tools wanted. */
    val wantsTools: Boolean = false
)

/** Outcome of capability-aware routing for one turn. */
sealed class LocalRouteDecision {

    /** Serve the turn on-device with these (possibly degraded) capabilities. */
    data class Route(
        val capabilities: LocalModelCapabilities,
        val degraded: List<String>
    ) : LocalRouteDecision()

    /**
     * Nothing on-device can serve this turn. [reason] is user-presentable and
     * names the way out (pick a vision model in the switcher, drop the image).
     */
    data class Refuse(val reason: String) : LocalRouteDecision()
}

object LocalModelRouter {

    /**
     * Hard context ceiling for on-device generation. A phone cannot hold a
     * 128k KV cache for a multi-GB model — the KV cache is what blows up
     * first, so we cap it far below the GGUF's declared maximum and say so.
     */
    const val MAX_CONTEXT_TOKENS = 4096

    /** Floor: below this, a chat turn is useless anyway. */
    const val MIN_CONTEXT_TOKENS = 512

    /** Conservative default when the GGUF declares no context length. */
    const val DEFAULT_CONTEXT_TOKENS = 2048

    /**
     * Architectures whose GGUF carries a vision tower. llama.cpp loads the
     * mmproj from a SEPARATE file, so even for these we only report vision as
     * "declared" — the loader decides whether the projector is present.
     */
    private val VISION_ARCHITECTURES = listOf(
        "llava", "qwen2vl", "minicpmv", "gemma3v", "moondream",
        "internvl", "idefics", "smolvlm", "paligemma", "mllama"
    )

    /** Context window actually used for a declared (or missing) GGUF length. */
    fun effectiveContextTokens(declaredContextLength: Long?): Int =
        (declaredContextLength?.toInt() ?: DEFAULT_CONTEXT_TOKENS)
            .coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)

    /** Vision is ON only when the ARCHITECTURE itself declares a vision tower. */
    fun architectureSupportsVision(architecture: String?): Boolean {
        val arch = architecture ?: return false
        return VISION_ARCHITECTURES.any { arch.contains(it, ignoreCase = true) }
    }

    /** Honest capability set for one registered model. */
    fun capabilities(record: LocalModelRecord): LocalModelCapabilities {
        val notes = mutableListOf<String>()
        val declared = record.contextLength?.toInt()
        val context = effectiveContextTokens(record.contextLength)

        val vision = architectureSupportsVision(record.architecture)
        notes += if (vision) {
            "Vision tower declared by ${record.architecture}"
        } else {
            "Text only — images are not supported"
        }
        notes += if (record.supportsTools) {
            "Tool-calling chat template found in the GGUF metadata"
        } else {
            "No tool-calling support — tools are unavailable"
        }
        if (declared != null && declared > MAX_CONTEXT_TOKENS) {
            notes += "Context capped at $MAX_CONTEXT_TOKENS tokens (model declares $declared)"
        }
        return LocalModelCapabilities(
            modelId = record.id,
            displayName = record.displayName,
            supportsTools = record.supportsTools,
            supportsVision = vision,
            contextWindowTokens = context,
            declaredContextTokens = declared,
            notes = notes
        )
    }

    /**
     * Routes ONE turn. Images on a text-only model are refused up front with
     * an actionable reason; everything else degrades visibly.
     */
    fun decide(
        record: LocalModelRecord,
        request: LocalRouteRequest
    ): LocalRouteDecision {
        val capabilities = capabilities(record)
        if (request.hasImageAttachments && !capabilities.supportsVision) {
            return LocalRouteDecision.Refuse(
                "\"${record.displayName}\" is a text-only on-device model, so the image " +
                    "cannot be read. Pick a vision model in the top-bar selector, or " +
                    "remove the image and send again."
            )
        }
        val degraded = capabilities.notes.toMutableList()
        if (request.wantsTools && !capabilities.supportsTools) {
            degraded += "Tools disabled for this turn — the model's template has no tool support"
        }
        return LocalRouteDecision.Route(capabilities, degraded)
    }

    /** "On-device · Qwen2.5 1B Instruct · 4096 ctx" — the timeline row title. */
    fun timelineLabel(capabilities: LocalModelCapabilities): String =
        "On-device · ${capabilities.displayName} · ${capabilities.contextWindowTokens} ctx"

    /**
     * One-line reason shown under the label: what the router resolved AND why
     * anything was degraded (including the live thermal/battery throttle).
     */
    fun timelineDetail(
        capabilities: LocalModelCapabilities,
        degraded: List<String>,
        throttleNote: String?
    ): String = (degraded + listOfNotNull(throttleNote)).joinToString(" · ")
}