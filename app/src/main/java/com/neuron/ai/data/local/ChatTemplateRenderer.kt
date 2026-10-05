package com.neuron.ai.data.local

/**
 * Turns a conversation into the flat string an instruct-tuned model was
 * actually trained on.
 *
 * The order of preference mirrors llama.cpp's own `common_chat_templates`:
 *
 *  1. the MODEL'S OWN Jinja template, read from its GGUF metadata and applied
 *     natively via `llama_chat_apply_template` — the only rendering that is
 *     correct for a Llama-3 / Mistral / Gemma / Qwen checkpoint;
 *  2. llama.cpp's ChatML renderer, reproduced here in pure Kotlin, used when the
 *     GGUF carries no template or llama.cpp does not recognize it.
 *
 * Bug this exists to fix: the app used to hand-write `system\n…\nassistant\n`
 * with no control tokens at all. Chat control tokens are not cosmetic — they
 * are the delimiters the model was fine-tuned to turn on and off role
 * behaviour with, and a template-foreign prompt yields a model that answers
 * the system prompt, continues the conversation, or emits nothing.
 *
 * Deliberately pure and side-effect free so it is unit-tested (see
 * ChatTemplateRendererTest) rather than trusted because it "looks right".
 */
object ChatTemplateRenderer {

    /**
     * One rendered turn. Roles are the strings llama.cpp's template renderer
     * understands: `system`, `user`, `assistant`.
     */
    data class Turn(val role: String, val content: String) {
        companion object {
            const val SYSTEM = "system"
            const val USER = "user"
            const val ASSISTANT = "assistant"
        }
    }

    /**
     * llama.cpp's `LLM_CHAT_TEMPLATE_CHATML` (`src/llama-chat.cpp`), byte for
     * byte. Deliberately NOT prepending BOS: the tokenizer is called with
     * `add_special = true` and adds it, and a doubled BOS is a real prompt
     * regression.
     */
    private const val CHATML_SYSTEM = "<|im_start|>system\n%s<|im_end|>\n"
    private const val CHATML_USER = "<|im_start|>user\n%s<|im_end|>\n"
    private const val CHATML_ASSISTANT = "<|im_start|>assistant\n%s<|im_end|>\n"
    private const val CHATML_ASSISTANT_PREFIX = "<|im_start|>assistant\n"

    /**
     * The generator prefix a ChatML model expects to see before it starts
     * writing. llama.cpp decides between "already open" and "needs opening"
     * from the last message; we do the same, so a conversation that already
     * ends on an assistant turn is not opened twice.
     */
    fun renderChatMl(turns: List<Turn>, addAssistant: Boolean = true): String {
        val builder = StringBuilder()
        for (turn in turns) {
            val body = turn.content
            builder.append(
                when (turn.role) {
                    Turn.SYSTEM -> CHATML_SYSTEM.format(body)
                    Turn.ASSISTANT -> CHATML_ASSISTANT.format(body)
                    // A "tool" role is not part of ChatML; llama.cpp folds
                    // tool output into a user turn too.
                    else -> CHATML_USER.format(body)
                }
            )
        }
        if (addAssistant) builder.append(CHATML_ASSISTANT_PREFIX)
        return builder.toString()
    }

    /**
     * Appends the vision media marker to the content of the image-bearing turn.
     *
     * The marker is inserted INTO the turn, not after it: mtmd counts media
     * markers in the prompt and splices the image embeddings where they appear,
     * so a marker that sits after the turn's `im_end` is either dropped or
     * attributed to the wrong message.
     */
    fun withMediaMarker(content: String): String =
        if (content.contains(LocalEngineLoader.MEDIA_MARKER)) {
            content
        } else {
            content.trimEnd() + "\n" + LocalEngineLoader.MEDIA_MARKER
        }
}