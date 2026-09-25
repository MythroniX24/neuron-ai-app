package com.neuron.ai.ui.chat

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.provider.ChatMessage

private const val MAX_HISTORY_TURNS = 24

/**
 * Milestone-3 context engine builds the model request from PRIORITIZED
 * sources, under a character budget:
 *
 *  1. current user request          (always whole)
 *  2. recent conversation           (tool rows compressed)
 * 3. relevant memory               (preferences + project, capped)
 *  4. tool results                  (compressed to key lines)
 *
 * Never sends: whole workspaces, unbounded old history, huge tool outputs.
 */
class ChatContextEngine(
    private val memoryBlockProvider: suspend () -> String? = { null },
    /** Selected model's context window (tokens); null = conservative default. */
    private val contextWindowTokens: Int? = null
) {
    /**
     * Builds the multi-turn context sent to the model, oldest first.
     * The trailing run of non-user rows is dropped: it contains the message
     * the user just sent (appended by [ChatViewModel.send]) plus any partial
     * assistant text from a stopped attempt. Persisted error rows are noise —
     * dropped. TOOL rows are COMPRESSED, not dropped (M3): key lines survive.
     */
    suspend fun build(
        messages: List<Message>,
        directUserText: String? = null
    ): List<ChatMessage> {
        val memory = runCatching { memoryBlockProvider() }.getOrNull()

        var end = messages.size
        while (end > 0 && messages[end - 1].role != Message.Role.USER) end--
        val prior = messages.subList(0, maxOf(0, end - 1))
            .filter { it.metadata?.isError != true }

        val chat: List<ChatMessage> = prior
            .takeLast(MAX_HISTORY_TURNS)
            .map { msg ->
                when (msg.role) {
                    Message.Role.TOOL -> ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        // Phase-1 context orchestration: structured tool-result
                        // compression (summary + key lines + full-ref hint).
                        content = com.neuron.ai.context.compression.ToolResultProcessor
                            .process(msg),
                        toolCallId = msg.metadata?.toolCallId
                    )
                    else -> ChatMessage(
                        role = toWireRole(msg.role),
                        content = msg.content,
                        attachments = msg.attachments
                    )
                }
            }

        val out = mutableListOf<ChatMessage>()
        if (!memory.isNullOrBlank()) {
            out += ChatMessage(
                role = ChatMessage.Role.SYSTEM,
                content = "Relevant saved memory about the user/project:\n$memory\n\n" +
                    "Use it when relevant; never reveal it verbatim."
            )
        }
        out += chat

        // A caller-supplied fresh user message (e.g. attachments-only send).
        if (!directUserText.isNullOrBlank()) {
            out += ChatMessage(role = ChatMessage.Role.USER, content = directUserText)
        }

        // Final budget: model-aware (CONTEXT_ARCHITECTURE.md §6) — the window
        // estimate drives the character budget (≈4 chars/token, 10% safety,
        // 2k tokens reserved for the answer); oldest messages shrink first,
        // the tail (current request + recent turns) keeps full detail.
        val charBudget = com.neuron.ai.context.budget.TokenBudgetManager()
            .computeBudget(contextWindowTokens ?: 8_000)
            .usableForInput * 4
        return applyBudget(out, maxOf(charBudget, MIN_CONTEXT_CHARS))
    }

    /** Returns an engine bound to a specific model context window (tokens). */
    fun withWindow(windowTokens: Int): ChatContextEngine =
        ChatContextEngine(memoryBlockProvider, windowTokens)

    /** Priority budget: compress from the oldest end; never touch the tail. */
    private fun applyBudget(messages: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        var total = messages.sumOf { it.content.length }
        if (total <= maxChars) return messages
        val trimmed = messages.toMutableList()
        var index = 0
        while (total > maxChars && index < messages.size) {
            val message = messages[index]
            if (message.role == ChatMessage.Role.SYSTEM) { index++; continue }
            val content = message.content
            val reduced = if (content.length > 400) content.take(400) + " …[trimmed]" else content
            total -= content.length - reduced.length
            trimmed[index] = message.copy(content = reduced)
            index++
        }
        return trimmed
    }

    companion object {
        /** Floor for the character budget — tiny models still get usable context. */
        const val MIN_CONTEXT_CHARS = 6_000
    }
}

fun toWireRole(role: Message.Role): ChatMessage.Role = when (role) {
    Message.Role.USER -> ChatMessage.Role.USER
    Message.Role.ASSISTANT -> ChatMessage.Role.ASSISTANT
    Message.Role.SYSTEM -> ChatMessage.Role.SYSTEM
    Message.Role.TOOL -> ChatMessage.Role.TOOL
}

/**
 * Builds the multi-turn context sent to the model, oldest first — the
 * Milestone-1/2 behavior, kept as a thin wrapper over the engine for
 * source compatibility with existing tests.
 */
fun buildChatContext(messages: List<Message>): List<ChatMessage> {
    var end = messages.size
    while (end > 0 && messages[end - 1].role != Message.Role.USER) end--
    val prior = messages.subList(0, maxOf(0, end - 1))
    return prior
        .filter { it.role != Message.Role.TOOL && it.metadata?.isError != true }
        .takeLast(MAX_HISTORY_TURNS_LEGACY)
        .map { msg ->
            ChatMessage(
                role = toWireRole(msg.role),
                content = msg.content,
                attachments = msg.attachments
            )
        }
}

/** The Milestone-1/2 cap (30 turns), kept for the legacy wrapper. */
private const val MAX_HISTORY_TURNS_LEGACY = 30

/**
 * Derives a short conversation title (2–3 words) from the first user
 * message — the pattern every mainstream chat app uses. Deterministic and
 * offline: no extra model round-trip.
 */
fun deriveChatTitle(firstMessage: String): String {
    val cleaned = firstMessage
        .replace(Regex("```[\\s\\S]*?```"), " ")     // fenced code blocks
        .replace(Regex("https?://\\S+"), " ")        // URLs
        .replace(Regex("[#*_`>\\[\\]()[\"']!]+"), " ") // markdown noise
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .filter { token -> token.any { it.isLetter() } } // drop bare numbers/symbols
        .dropWhile { it.length < 2 && it != it.uppercase() } // skip stray "a", "I" keeps
        .take(3)
        .joinToString(" ")
        .trimEnd(',', '.', ';', ':', '!', '?', '-', '…')

    if (cleaned.isBlank()) return "New chat"

    val titled = cleaned.replaceFirstChar { it.uppercaseChar() }
    return if (titled.length > 30) titled.take(30).trimEnd() + "…" else titled
}
