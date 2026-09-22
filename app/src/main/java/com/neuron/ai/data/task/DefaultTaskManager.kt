package com.neuron.ai.data.task

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.task.Task
import com.neuron.ai.core.task.TaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process task manager (Phase 1). Each task runs in a supervised coroutine
 * so the user can stop it; FAILED tasks can be re-queued via [retry].
 */
class DefaultTaskManager(dispatchers: DispatcherProvider) : TaskManager {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val mutex = Mutex()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: Flow<List<Task>> = _tasks.asStateFlow()

    val runningTasks: Flow<List<Task>> =
        _tasks.map { list -> list.filter { it.status == Task.Status.RUNNING } }

    private val jobs = ConcurrentHashMap<String, Job>()

    override suspend fun create(title: String, conversationId: String?): Task =
        mutex.withLock {
            val now = System.currentTimeMillis()
            val task = Task(
                id = "task-" + UUID.randomUUID().toString().take(8),
                title = title,
                status = Task.Status.QUEUED,
                conversationId = conversationId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            _tasks.value = _tasks.value + task
            task
        }

    /**
     * Marks a task RUNNING and executes [block]; completion, failure and
     * cancellation are reflected in the task status automatically.
     */
    fun launch(taskId: String, onTaskCancelled: (() -> Unit)? = null, block: suspend () -> Unit) {
        val job = scope.launch {
            updateStatus(taskId, Task.Status.RUNNING)
            try {
                block()
                withContext(NonCancellable) { updateStatus(taskId, Task.Status.DONE) }
            } catch (e: CancellationException) {
                // The coroutine is already cancelled, so status writes must be NonCancellable.
                withContext(NonCancellable) { updateStatus(taskId, Task.Status.CANCELLED) }
                onTaskCancelled?.invoke()
                throw e
            } catch (t: Throwable) {
                withContext(NonCancellable) { updateStatus(taskId, Task.Status.FAILED) }
            }
        }
        jobs[taskId] = job
    }

    override suspend fun updateStatus(taskId: String, status: Task.Status) = mutex.withLock {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) task.copy(status = status, updatedAtEpochMs = System.currentTimeMillis())
            else task
        }
        Unit
    }

    override suspend fun cancel(taskId: String) {
        // No lock here: cancelling must never block the cancelled job's own
        // catch-handler from acquiring the mutex for its status update.
        jobs.remove(taskId)?.cancel()
    }

    /** Re-queues a FAILED task and re-runs it with [block]. */
    suspend fun retry(taskId: String, block: suspend () -> Unit) {
        updateStatus(taskId, Task.Status.QUEUED)
        launch(taskId, block = block)
    }

    /** Drops finished tasks from the list (housekeeping). */
    suspend fun clearFinished() = mutex.withLock {
        _tasks.value = _tasks.value.filter {
            it.status == Task.Status.QUEUED || it.status == Task.Status.RUNNING
        }
        Unit
    }
}
