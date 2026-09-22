package com.neuron.ai.core.task

import kotlinx.coroutines.flow.Flow

/**
 * A unit of user-visible work that may outlive a single chat turn.
 * Phase 2 turns this into true background execution; Phase 0 fixes the model.
 */
data class Task(
    val id: String,
    val title: String,
    val status: Status,
    val conversationId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }
}

/** Creates and observes tasks. */
interface TaskManager {
    val tasks: Flow<List<Task>>
    suspend fun create(title: String, conversationId: String?): Task
    suspend fun updateStatus(taskId: String, status: Task.Status)
    suspend fun cancel(taskId: String)
}
