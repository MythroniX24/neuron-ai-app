package com.neuron.ai.data.local

/**
 * Per-model prompt customization for on-device models.
 *
 * The engine has no sampler knobs exposed over JNI, so the settings that are
 * surfaced are the ones that genuinely change the prompt: a user-authored
 * instruction block and a reply-length style. Both are merged into the
 * conversation's system turn, preserving any system context the agent already
 * added instead of replacing it.
 *
 * Pure functions on purpose — unit-tested without Android or the native engine.
 */
enum class LocalReplyStyle(val label: String, val instruction: String?) {
    BALANCED("Balanced", null),
    CONCISE(
        "Concise",
        "Keep the answer concise: short paragraphs, no filler, and only the detail needed to act."
    ),
    DETAILED(
        "Detailed",
        "Explain thoroughly: use clear structure, concrete examples, and mention important edge cases."
    );

    companion object {
        fun fromNameOrDefault(name: String?): LocalReplyStyle =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

object LocalPromptOptions {

    const val MAX_INSTRUCTIONS_CHARS = 2_000

    /** Extra system text for one model; null when nothing was customized. */
    fun behaviorPrompt(instructions: String?, replyStyle: String?): String? {
        val clean = instructions?.trim()?.take(MAX_INSTRUCTIONS_CHARS).orEmpty()
        val style = LocalReplyStyle.fromNameOrDefault(replyStyle).instruction
        val parts = listOfNotNull(
            clean.takeIf { it.isNotBlank() },
            style
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Merges the model's instructions into the first system turn, or prepends
     * a system turn when the conversation has none. Other turns are untouched.
     */
    fun applyToTurns(
        turns: List<ChatTemplateRenderer.Turn>,
        instructions: String?,
        replyStyle: String?
    ): List<ChatTemplateRenderer.Turn> {
        val extra = behaviorPrompt(instructions, replyStyle) ?: return turns
        val systemIndex = turns.indexOfFirst { it.role == ChatTemplateRenderer.Turn.SYSTEM }
        if (systemIndex == -1) {
            return listOf(ChatTemplateRenderer.Turn(ChatTemplateRenderer.Turn.SYSTEM, extra)) + turns
        }
        val existing = turns[systemIndex].content.trim()
        val merged = if (existing.isBlank()) extra else "$existing\n\n$extra"
        return turns.toMutableList().also { list ->
            list[systemIndex] = list[systemIndex].copy(content = merged)
        }
    }
}
