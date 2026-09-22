package com.neuron.ai.data.task

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.task.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the task lifecycle (queued → running → done/failed/cancelled) on a test dispatcher. */
class DefaultTaskManagerTest {

    /** All dispatchers share the TestScope scheduler so runCurrent() drives them. */
    private fun TestScope.testDispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        override val io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        override val default: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    }

    @Test
    fun `task completes successfully`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        val task = manager.create("Summarize file", conversationId = null)
        assertEquals(Task.Status.QUEUED, manager.tasks.first().single().status)

        manager.launch(task.id) { /* no-op work */ }
        runCurrent()

        assertEquals(Task.Status.DONE, manager.tasks.first().single().status)
    }

    @Test
    fun `task failure is surfaced as FAILED status`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        val task = manager.create("Broken job", conversationId = null)

        manager.launch(task.id) { throw IllegalStateException("boom") }
        runCurrent()

        assertEquals(Task.Status.FAILED, manager.tasks.first().single().status)
    }

    @Test
    fun `cancel transitions a running task to CANCELLED`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        val task = manager.create("Long job", conversationId = null)

        manager.launch(task.id) { delay(10_000) }
        runCurrent()
        assertEquals(Task.Status.RUNNING, manager.tasks.first().single().status)

        manager.cancel(task.id)
        runCurrent()

        assertEquals(Task.Status.CANCELLED, manager.tasks.first().single().status)
    }

    @Test
    fun `retry re-runs a failed task`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        val task = manager.create("Flaky job", conversationId = null)

        var attempts = 0
        manager.launch(task.id) {
            attempts++
            if (attempts == 1) throw IllegalStateException("first fails")
        }
        runCurrent()
        assertEquals(Task.Status.FAILED, manager.tasks.first().single().status)

        manager.retry(task.id) { attempts++ }
        runCurrent()

        assertEquals(Task.Status.DONE, manager.tasks.first().single().status)
        assertEquals(2, attempts)
    }

    @Test
    fun `clearFinished keeps only active tasks`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        val done = manager.create("Done job", conversationId = null)
        val running = manager.create("Active job", conversationId = null)

        manager.launch(done.id) { /* completes immediately */ }
        manager.launch(running.id) { delay(60_000) }
        runCurrent()

        manager.clearFinished()

        val remaining = manager.tasks.first()
        assertEquals(listOf(running.id), remaining.map { it.id })
        assertEquals(Task.Status.RUNNING, remaining.single().status)
    }

    @Test
    fun `cancel on unknown id is a safe no-op`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        manager.cancel("task-unknown")
        assertTrue(manager.tasks.first().isEmpty())
    }

    @Test
    fun `advanceTimeBy lets delayed work finish`() = runTest {
        val manager = DefaultTaskManager(testDispatchers())
        val task = manager.create("Timed job", conversationId = null)

        manager.launch(task.id) { delay(500) }
        advanceTimeBy(600)
        runCurrent()

        assertEquals(Task.Status.DONE, manager.tasks.first().single().status)
    }
}
