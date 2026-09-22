package com.neuron.ai.ui.chat

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.provider.ChatMessage

/** Max prior turns sent to the model as context. */
private const val MAX_HISTORY_TURNS = 30

fun toWireRole(role: Message.Role): ChatMessage.Role = when (role) {
    Message.Role.USER -> ChatMessage.Role.USER
    Message.Role.ASSISTANT -> ChatMessage.Role.ASSISTANT
    Message.Role.SYSTEM -> ChatMessage.Role.SYSTEM
    Message.Role.TOOL -> ChatMessage.Role.TOOL
}

/**
 * Builds the multi-turn context sent to the model, oldest first.
 *
 * The trailing run of non-user rows is dropped: it contains the message the
 * user just sent (appended by [ChatViewModel.send]) plus any partial
 * assistant text from a stopped attempt. TOOL rows and persisted error
 * messages are excluded — dangling tool results are rejected by several
 * providers and error rows are noise for the model.
 */
fun buildChatContext(messages: List<Message>): List<ChatMessage> {
    var end = messages.size
    while (end > 0 && messages[end - 1].role != Message.Role.USER) end--
    val prior = messages.subList(0, maxOf(0, end - 1))
    return prior
        .filter { it.role != Message.Role.TOOL && it.metadata?.isError != true }
        .takeLast(MAX_HISTORY_TURNS)
        .map { msg ->
            ChatMessage(
                role = toWireRole(msg.role),
                content = msg.content,
                attachments = msg.attachments
            )
        }
}

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
