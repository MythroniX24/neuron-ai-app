package com.neuron.ai.context.compression

import com.neuron.ai.core.conversation.Message

/**
 * Tool-result compression (CONTEXT_ARCHITECTURE.md §9, the highest-payoff
 * Phase-1 piece): big tool payloads never re-enter context raw on every
 * later turn.
 *
 * [ProcessedToolResult] is what ENTERS context (summary + relevant lines +
 * pointer); the full text stays in the conversation log and remains
 * reachable through the persisted TOOL message.
 */
data class ProcessedToolResult(
    val toolName: String,
    val callId: String,
    /** One-line outcome, e.g. "exit=0" or "Permission denied". */
    val summary: String,
    /** The lines that actually matter (errors, codes, sources, paths). */
    val relevantLines: List<String>,
    /** Total original size — surfaced to the model as an availability hint. */
    val originalChars: Int
) {
    fun render(): String = buildString {
        append("[TOOL ").append(toolName).append("] ").append(summary)
        if (relevantLines.isNotEmpty()) {
            append('\n')
            relevantLines.forEach { line ->
                append("  ").append(line).append('\n')
            }
        }
        if (originalChars > COMPRESS_THRESHOLD_CHARS) {
            append("  (full output ").append(originalChars)
                .append(" chars — ask to read the full tool output if needed)")
        }
    }.trimEnd()

    companion object {
        const val COMPRESS_THRESHOLD_CHARS = 1_200
    }
}

/**
 * Stateless processor turning a persisted TOOL message into its
 * context-sized form. Deterministic — unit-testable without fixtures.
 */
object ToolResultProcessor {

    private val keyLinePatterns = listOf(
        Regex("^\\s*exit=\\s*-?\\d+"),          // terminal exit codes
        Regex("^\\s*\\[err]", RegexOption.IGNORE_CASE),
        Regex("^\\s*(error|exception|failed)\\b", RegexOption.IGNORE_CASE),
        Regex("^\\s*(SOURCE|TITLE:|URL:|PATH:)"), // web/browser citations
        Regex("^\\s*(✓|✗|⟳)"),                    // agent activity outcomes
        Regex("^\\s*(warning)\\b", RegexOption.IGNORE_CASE)
    )

    /** True when the message is small enough to pass through untouched. */
    fun needsProcessing(message: Message): Boolean =
        message.role == Message.Role.TOOL &&
            message.content.length > ProcessedToolResult.COMPRESS_THRESHOLD_CHARS

    /**
     * Compresses a TOOL message: structured summary + key lines, hard-capped.
     * Smaller tool messages pass through unchanged.
     */
    fun process(message: Message, maxKeyLines: Int = 12, maxChars: Int = 1_200): String {
        if (!needsProcessing(message)) return message.content

        val toolName = message.metadata?.toolName ?: "tool"
        val callId = message.metadata?.toolCallId ?: message.id
        val content = message.content
        val lines = content.lines()

        val summary = lines.firstOrNull { it.isNotBlank() }?.take(120) ?: "done"
        val relevant = lines.filter { line -> keyLinePatterns.any { it.containsMatchIn(line) } }
            .map { it.trim().take(160) }
            .distinct()
            .take(maxKeyLines)

        val processed = ProcessedToolResult(
            toolName = toolName,
            callId = callId,
            summary = summary,
            relevantLines = relevant,
            originalChars = content.length
        )
        return processed.render().take(maxChars)
    }
}
