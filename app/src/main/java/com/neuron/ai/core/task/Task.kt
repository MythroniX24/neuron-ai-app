package com.neuron.ai.core.task

import kotlinx.coroutines.flow.Flow

/**
 * A unit of user-visible work that may outlive a single chat turn.
 * Tasks mirror agent runs; Milestone 2 adds background persistence.
 */
data class Task(
    val id: String,
    val title: String,
    val status: Status,
    val conversationId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Latest one-line activity, e.g. "Reading file: notes.md". */
    val activity: String? = null,
    /** Number of completed agent steps, when known. */
    val completedSteps: Int? = null,
    val totalSteps: Int? = null,
    val error: String? = null
) {
    enum class Status { QUEUED, RUNNING, WAITING_FOR_PERMISSION, DONE, FAILED, CANCELLED }

    val isFinished: Boolean
        get() = this == Status.DONE || this == Status.FAILED || this == Status.CANCELLED

    val isTerminalFailure: Boolean
        get() = this == Status.FAILED
}

/** Creates, launches, observes and cancels tasks. */
interface TaskManager {
    val tasks: Flow<List<Task>>
    suspend fun create(title: String, conversationId: String?): Task
    suspend fun updateStatus(taskId: String, status: Task.Status)
    suspend fun cancel(taskId: String)
}
