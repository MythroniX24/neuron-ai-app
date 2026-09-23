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
 * Task manager (Milestone 1): supervised coroutines, persisted via
 * [TaskRecordStore], crash recovery on startup, live activity reporting.
 */
class DefaultTaskManager(
    dispatchers: DispatcherProvider,
    private val store: TaskRecordStore? = null
) : TaskManager {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val mutex = Mutex()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: Flow<List<Task>> = _tasks.asStateFlow()

    val runningTasks: Flow<List<Task>> =
        _tasks.map { list -> list.filter { it.status == Task.Status.RUNNING } }

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Restores persisted tasks; stale RUNNING rows become FAILED (crash recovery). */
    suspend fun restore() {
        val persisted = store?.load() ?: return
        val recovered = persisted.map { record ->
            if (record.status == Task.Status.RUNNING || record.status == Task.Status.QUEUED) {
                record.copy(status = Task.Status.FAILED, error = "Interrupted by app restart.")
            } else {
                record
            }
        }
        mutex.withLock { _tasks.value = recovered }
        store.save(recovered)
    }

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
            persistLocked()
            task
        }

    override suspend fun updateStatus(taskId: String, status: Task.Status) =
        mutate(taskId) { it.copy(status = status) }

    /** One-line activity for the tasks screen. */
    suspend fun reportActivity(taskId: String, activity: String) =
        mutate(taskId) { it.copy(activity = activity.take(120)) }

    suspend fun reportProgress(taskId: String, completedSteps: Int, totalSteps: Int?) =
        mutate(taskId) { it.copy(completedSteps = completedSteps, totalSteps = totalSteps) }

    suspend fun reportError(taskId: String, message: String) =
        mutate(taskId) { it.copy(error = message.take(300)) }

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
                withContext(NonCancellable) { updateStatus(taskId, Task.Status.CANCELLED) }
                onTaskCancelled?.invoke()
                throw e
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    updateStatus(taskId, Task.Status.FAILED)
                    reportError(taskId, t.message ?: "Unknown failure")
                }
            }
        }
        jobs[taskId] = job
    }

    override suspend fun cancel(taskId: String) {
        // No lock: cancelling must never block the cancelled job's own
        // catch-handler from acquiring the mutex for its status update.
        jobs.remove(taskId)?.cancel()
    }

    /** Re-queues a FAILED task and re-runs it with [block]. */
    suspend fun retry(taskId: String, block: suspend () -> Unit) {
        mutate(taskId) { it.copy(error = null, activity = null) }
        updateStatus(taskId, Task.Status.QUEUED)
        launch(taskId, block = block)
    }

    /** Drops finished tasks from the list (housekeeping). */
    suspend fun clearFinished() = mutex.withLock {
        _tasks.value = _tasks.value.filter {
            it.status == Task.Status.QUEUED || it.status == Task.Status.RUNNING
        }
        persistLocked()
        Unit
    }

    private suspend fun mutate(taskId: String, transform: (Task) -> Task) = mutex.withLock {
        _tasks.value = _tasks.value.map { if (it.id == taskId) transform(it).copy(updatedAtEpochMs = System.currentTimeMillis()) else it }
        persistLocked()
        Unit
    }

    private suspend fun persistLocked() {
        store?.save(_tasks.value)
    }
}
