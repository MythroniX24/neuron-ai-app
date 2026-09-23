package com.neuron.ai.data.task

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.task.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies task persistence: roundtrip through the store and crash recovery. */
class TaskRecordStoreTest {

    /** In-memory fake standing in for the Room-backed store. */
    private class FakeStore : TaskRecordStore {
        var data: List<Task> = emptyList()
        override suspend fun load(): List<Task> = data
        override suspend fun save(tasks: List<Task>) { data = tasks }
    }

    /** All dispatchers share the TestScope scheduler so runCurrent() drives them. */
    private fun TestScope.testDispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        override val io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        override val default: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    }

    private fun task(id: String, status: Task.Status) = Task(
        id = id,
        title = "Job $id",
        status = status,
        conversationId = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 2_000L
    )

    @Test
    fun `launching a task persists its lifecycle`() = runTest {
        val store = FakeStore()
        val manager = DefaultTaskManager(testDispatchers(), store)
        val task = manager.create("Persisted job", conversationId = null)

        manager.launch(task.id) { /* completes */ }
        runCurrent()

        val persisted = store.data.single()
        assertEquals(Task.Status.DONE, persisted.status)
        assertEquals(task.id, persisted.id)
    }

    @Test
    fun `restore marks stale running tasks as failed`() = runTest {
        val store = FakeStore()
        store.data = listOf(
            task("t1", Task.Status.RUNNING),
            task("t2", Task.Status.DONE)
        )
        val manager = DefaultTaskManager(testDispatchers(), store)

        manager.restore()

        val restored = manager.tasks.first()
        assertEquals(2, restored.size)
        assertEquals(Task.Status.FAILED, restored.first { it.id == "t1" }.status)
        assertEquals("Interrupted by app restart.", restored.first { it.id == "t1" }.error)
        assertEquals(Task.Status.DONE, restored.first { it.id == "t2" }.status)
    }

    @Test
    fun `restore with empty store yields no tasks`() = runTest {
        val manager = DefaultTaskManager(testDispatchers(), FakeStore())
        manager.restore()
        assertTrue(manager.tasks.first().isEmpty())
    }
}
