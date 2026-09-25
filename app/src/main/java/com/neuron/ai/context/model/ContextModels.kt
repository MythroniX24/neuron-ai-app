package com.neuron.ai.context.model

/**
 * Context-orchestration models (Phase 1 of CONTEXT_ARCHITECTURE.md).
 *
 * Deliberately lightweight vocabulary: the orchestrator assembles a
 * right-sized context from prioritized items instead of shipping the whole
 * raw conversation.
 */

/** Hard priority tiers — lower level wins and is never dropped first. */
enum class ContextPriority(val level: Int) {
    SYSTEM(0),
    CURRENT_REQUEST(1),
    TASK_STATE(2),
    RECENT_CHAT(3),
    WORKSPACE(4),
    MEMORY(5),
    OLDER_HISTORY(6),
    LOW_VALUE(7)
}

/** Where a context item came from. */
enum class ContextSourceType {
    SYSTEM, CONVERSATION, MEMORY, WORKSPACE, TASK, TOOL_RESULT, ATTACHMENT
}

/** One candidate unit of context. */
data class ContextItem(
    val id: String,
    val sourceType: ContextSourceType,
    val sourceId: String,
    val priority: ContextPriority,
    /** 0f when relevance scoring is not applicable (e.g. system context). */
    val relevanceScore: Float = 0f,
    /** Cheap char-based estimate; the budgeter converts to tokens. */
    val content: String,
    val timestampMs: Long = 0L,
    val workspaceId: String? = null,
    val conversationId: String = "",
    val taskId: String? = null
) {
    val tokenEstimate: Int get() = content.length / 4
}
