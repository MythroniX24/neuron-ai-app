package com.neuron.ai.data.db

import com.neuron.ai.core.task.Task
import com.neuron.ai.data.task.TaskRecordStore

/** Room-backed persistence for agent tasks; survives process death. */
class RoomTaskRecordStore(private val dao: TaskDao) : TaskRecordStore {

    override suspend fun load(): List<Task> = dao.getAll().map { it.toDomain() }

    override suspend fun save(tasks: List<Task>) {
        dao.replaceAll(tasks.map { it.toEntity() })
    }
}

internal fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    status = runCatching { Task.Status.valueOf(status) }.getOrDefault(Task.Status.FAILED),
    conversationId = conversationId,
    activity = activity,
    completedSteps = completedSteps,
    totalSteps = totalSteps,
    error = error,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

internal fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    status = status.name,
    conversationId = conversationId,
    activity = activity,
    completedSteps = completedSteps,
    totalSteps = totalSteps,
    error = error,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)
