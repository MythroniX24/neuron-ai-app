package com.neuron.ai.data.local

/** What a turn needs from an on-device model — the axis routing rules map. */
enum class LocalTurnKind { TEXT, TOOLS, VISION }

/**
 * Milestone 9 — per-model routing rules, the pure half.
 *
 * Users end up with several GGUF files and different jobs for them: a tiny
 * model for quick replies, a tool-capable one for the agent loop, a vision
 * model for photos. Rather than making them re-pick the model every time, a
 * rule says "use this model for these kinds of turn", and the app routes the
 * turn itself.
 *
 * Rules are ordered: the FIRST rule that serves the turn AND names a usable,
 * capable model wins. With no rule at all the fallback is capability-based
 * (vision turns need a vision-capable model), then first-enabled — i.e. the
 * behaviour from earlier milestones still holds.
 */
data class ModelRoutingRule(
    val modelId: String,
    val text: Boolean = true,
    val tools: Boolean = false,
    val vision: Boolean = false
) {
    fun serves(kind: LocalTurnKind): Boolean = when (kind) {
        LocalTurnKind.TEXT -> text
        LocalTurnKind.TOOLS -> tools
        LocalTurnKind.VISION -> vision
    }

    fun with(kind: LocalTurnKind, enabled: Boolean): ModelRoutingRule = when (kind) {
        LocalTurnKind.TEXT -> copy(text = enabled)
        LocalTurnKind.TOOLS -> copy(tools = enabled)
        LocalTurnKind.VISION -> copy(vision = enabled)
    }
}

object LocalRoutingRules {

    /**
     * Picks the model that should serve [kind], or null when nothing local can.
     *
     * @param candidates registered models (enabled or not — the filter is here)
     * @param supportsVision capability probe (architecture + projector file)
     */
    fun pick(
        kind: LocalTurnKind,
        rules: List<ModelRoutingRule>,
        candidates: List<LocalModelRecord>,
        supportsVision: (LocalModelRecord) -> Boolean
    ): LocalModelRecord? {
        val byId = candidates.associateBy { it.id }
        val enabled = candidates.filter { it.enabledForChat }
        if (enabled.isEmpty()) return null

        // 1. The first matching rule wins, provided its model is enabled and
        //    actually capable of the turn.
        rules.firstOrNull { rule ->
            rule.serves(kind) &&
                byId[rule.modelId]?.enabledForChat == true &&
                (kind != LocalTurnKind.VISION || supportsVision(byId[rule.modelId]!!))
        }?.let { return byId[it.modelId] }

        // 2. No rule: capability fallback, so a vision turn never lands on a
        //    text-only model just because no rule was set up.
        return when (kind) {
            LocalTurnKind.VISION -> enabled.firstOrNull { supportsVision(it) }
            else -> enabled.first()
        }
    }

    /** The stored rule for [modelId], or the default (text only) when unset. */
    fun ruleFor(rules: List<ModelRoutingRule>, modelId: String): ModelRoutingRule =
        rules.firstOrNull { it.modelId == modelId }
            ?: ModelRoutingRule(modelId = modelId, text = false)
}
