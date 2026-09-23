package com.neuron.ai.ui.chat

import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.AIProvider

/**
 * AI-generated chat names (2-3 words) from the first user message, the way
 * mainstream chat apps do it. Falls back to the offline heuristic if the
 * model call fails — naming must never block or break a chat.
 */
object AiNaming {

    private const val MAX_TITLE_CHARS = 30

    suspend fun generate(
        firstMessage: String,
        provider: AIProvider,
        modelId: String
    ): String? {
        val prompt = "Create a very short chat title (2 to 3 words, max 30 characters) " +
            "for a conversation starting with the message below. " +
            "Reply with ONLY the title — no quotes, no punctuation at the end.\n\n" +
            firstMessage.take(400)

        return runCatching {
            val completion = provider.complete(
                CompletionRequest(
                    model = Model(id = modelId, displayName = modelId),
                    messages = listOf(
                        ChatMessage(
                            role = ChatMessage.Role.SYSTEM,
                            content = "You name chat conversations. Output only the title."
                        ),
                        ChatMessage(role = ChatMessage.Role.USER, content = prompt)
                    ),
                    temperature = 0.3,
                    maxOutputTokens = 24
                )
            )
            sanitize(completion.message.content)
        }.getOrNull() ?: deriveChatTitle(firstMessage).takeIf { it != "New chat" }
    }

    /** Cleans model output: strips quotes/markdown, caps length, guards empties. */
    fun sanitize(raw: String): String? {
        val cleaned = raw
            .replace(Regex("[\"'`*_]"), "")
            .trim()
            .trimEnd('.', '!', '?', ',', ':', ';', '-')
            .replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty() || !cleaned.any { it.isLetterOrDigit() }) return null
        val words = cleaned.split(" ")
        val titled = if (words.size > 4) words.take(3).joinToString(" ") else cleaned
        val finalTitle = if (titled.length > MAX_TITLE_CHARS) {
            titled.take(MAX_TITLE_CHARS).trimEnd() + "…"
        } else {
            titled
        }
        return finalTitle.replaceFirstChar { it.uppercaseChar() }
    }
}
