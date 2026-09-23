package com.neuron.ai.data.task

import com.neuron.ai.core.task.Task

/**
 * Storage seam behind task persistence. Milestone 1 ships a Room
 * implementation; tests use in-memory fakes. Kept narrow so Milestone 2 can
 * swap in a richer store (queue metadata, scheduling) without touching the
 * task manager.
 */
interface TaskRecordStore {
    suspend fun load(): List<Task>
    suspend fun save(tasks: List<Task>)
}
