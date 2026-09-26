package com.neuron.ai.context.providers

import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority
import com.neuron.ai.context.model.ContextSourceType
import com.neuron.ai.context.compression.ToolResultProcessor
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.memory.MemoryStore
import com.neuron.ai.core.memory.MemoryType
import com.neuron.ai.core.task.Task

/**
 * Context providers (CONTEXT_ARCHITECTURE.md §3–§5): THIN adapters over the
 * existing managers. Each fetches raw candidates and translates them into
 * [ContextItem]s — no business logic is duplicated here.
 *
 * Isolation (§10): every item carries conversationId/workspaceId; memory and
 * task providers filter strictly by those ids so nothing crosses scopes.
 */

/**
 * Maps conversation messages into prioritized items:
 * SYSTEM → P0, current user request → P1, TOOL rows → P2 (compressed),
 * everything else → P3. Persisted error rows are dropped as noise.
 */
class ConversationContextProvider {

    fun fetch(
        messages: List<Message>,
        conversationId: String,
        currentUserId: String?
    ): List<ContextItem> = messages.mapNotNull { message ->
        // Persisted error banners never help the model.
        if (message.metadata?.isError == true) return@mapNotNull null

        val priority = when {
            message.id == currentUserId -> ContextPriority.CURRENT_REQUEST
            message.role == Message.Role.SYSTEM -> ContextPriority.SYSTEM
            message.role == Message.Role.TOOL -> ContextPriority.TASK_STATE
            else -> ContextPriority.RECENT_CHAT
        }
        ContextItem(
            id = message.id,
            sourceType = when (message.role) {
                Message.Role.TOOL -> ContextSourceType.TOOL_RESULT
                Message.Role.SYSTEM -> ContextSourceType.SYSTEM
                else -> ContextSourceType.CONVERSATION
            },
            sourceId = message.id,
            priority = priority,
            relevanceScore = 0f,
            // Tool rows enter context pre-compressed (§9, Phase 1).
            content = if (message.role == Message.Role.TOOL) {
                ToolResultProcessor.process(message)
            } else {
                message.content
            },
            timestampMs = message.createdAtEpochMs,
            conversationId = conversationId,
            // Wire fidelity (Phase 3): the orchestrator can rebuild the exact
            // multi-turn message list, roles and tool-call ids included.
            wireRole = when (message.role) {
                Message.Role.USER -> com.neuron.ai.core.provider.ChatMessage.Role.USER
                Message.Role.ASSISTANT -> com.neuron.ai.core.provider.ChatMessage.Role.ASSISTANT
                Message.Role.SYSTEM -> com.neuron.ai.core.provider.ChatMessage.Role.SYSTEM
                Message.Role.TOOL -> com.neuron.ai.core.provider.ChatMessage.Role.TOOL
            },
            toolCallId = message.metadata?.toolCallId,
            attachments = message.attachments
        )
    }
}

/**
 * Wraps [MemoryStore.relevant] — already scope-filtered (matching scope +
 * global). Global prefs land at MEMORY, project facts keep the workspace id
 * so the orchestrator can verify isolation.
 */
class MemoryContextProvider(
    private val relevant: suspend (type: MemoryType, scopeId: String, limit: Int) -> List<com.neuron.ai.core.memory.MemoryEntry>
) {
    suspend fun fetch(
        conversationId: String,
        workspaceId: String?,
        query: String,
        limit: Int = 8
    ): List<ContextItem> {
        val prefs = runCatching {
            relevant(MemoryType.USER_PREFERENCE, "global", limit)
        }.getOrDefault(emptyList())

        val project = workspaceId?.let { ws ->
            runCatching { relevant(MemoryType.PROJECT, ws, limit) }.getOrDefault(emptyList())
        }.orEmpty()

        return (prefs + project).map { entry ->
            ContextItem(
                id = "mem-${entry.id}",
                sourceType = ContextSourceType.MEMORY,
                sourceId = entry.id,
                priority = ContextPriority.MEMORY,
                relevanceScore = 0.5f, // ranker refines via query overlap
                content = "${entry.key}: ${entry.value}",
                timestampMs = entry.updatedAtEpochMs,
                workspaceId = if (entry.type == MemoryType.PROJECT) entry.scopeId else null,
                conversationId = conversationId
            )
        }
    }
}

/**
 * Surfaces the LIVE task for this conversation (RUNNING / WAITING_FOR_PERMISSION
 * → TASK_STATE) and a recent DONE outcome as context. Tasks of other
 * conversations are never surfaced (§10).
 */
class TaskContextProvider(
    private val tasksProvider: suspend () -> List<Task>
) {
    suspend fun fetch(conversationId: String, taskId: String?): List<ContextItem> {
        val tasks = runCatching { tasksProvider() }.getOrDefault(emptyList())
        return tasks.mapNotNull { task ->
            val relevant = (task.conversationId == conversationId) ||
                (taskId != null && task.id == taskId)
            if (!relevant) return@mapNotNull null

            val live = !task.isFinished
            val body = buildString {
                append(task.title).append(" — ").append(task.status.name)
                task.activity?.let { append(" · ").append(it) }
                task.error?.let { append(" · error: ").append(it) }
            }
            ContextItem(
                id = "task-${task.id}",
                sourceType = ContextSourceType.TASK,
                sourceId = task.id,
                priority = if (live) ContextPriority.TASK_STATE else ContextPriority.OLDER_HISTORY,
                relevanceScore = if (live) 0.9f else 0.2f,
                content = body,
                timestampMs = task.updatedAtEpochMs,
                conversationId = conversationId,
                taskId = task.id
            )
        }
    }
}
