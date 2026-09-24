package com.neuron.ai.ui.chat

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.provider.ChatMessage

private const val MAX_HISTORY_TURNS = 24
private const val MAX_CONTEXT_CHARS = 24_000

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
    private val memoryBlockProvider: suspend () -> String? = { null }
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
                        // Compression: keep head + the most informative lines.
                        content = compressToolResult(msg.content),
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

        // Final budget: oldest messages shrink first; the tail (current
        // request + recent turns) keeps full detail.
        return applyBudget(out)
    }

    /** Priority budget: compress from the oldest end; never touch the tail. */
    private fun applyBudget(messages: List<ChatMessage>): List<ChatMessage> {
        var total = messages.sumOf { it.content.length }
        if (total <= MAX_CONTEXT_CHARS) return messages
        var index = 0
        while (total > MAX_CONTEXT_CHARS && index < messages.size) {
            val message = messages[index]
            if (message.role == ChatMessage.Role.SYSTEM) { index++; continue }
            val content = message.content
            val reduced = if (content.length > 400) content.take(400) + " …[trimmed]" else content
            total -= content.length - reduced.length
            messages[index] = message.copy(content = reduced)
            index++
        }
        return messages
    }

    companion object {
        /**
         * Tool-result compression: keeps structured headers (exit=, SOURCE,
         * TITLE/URL, [err] lines) and a bounded tail — the model keeps what it
         * needs to react, without the full payload.
         */
        fun compressToolResult(content: String, maxChars: Int = 1_200): String {
            if (content.length <= maxChars) return content
            val lines = content.lines()
            val keyLines = lines.filter { line ->
                val t = line.trimStart()
                t.startsWith("exit=") || t.startsWith("SOURCE") ||
                    t.startsWith("TITLE:") || t.startsWith("URL:") ||
                    t.startsWith("[") || t.startsWith("[err]") || t.startsWith("✓") ||
                    t.startsWith("error") || t.startsWith("Error")
            }
            val head = lines.take(20)
            val tail = lines.takeLast(10)
            val parts = buildList {
                addAll(keyLines.distinct().take(20))
                add("---")
                addAll(head)
                if (lines.size > 30) add("…")
                addAll(tail)
            }
            return parts.joinToString("\n").take(maxChars)
        }
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
        .takeLast(MAX_HISTORY_TURNS)
        .map { msg ->
            ChatMessage(
                role = toWireRole(msg.role),
                content = msg.content,
                attachments = msg.attachments
            )
        }
}
