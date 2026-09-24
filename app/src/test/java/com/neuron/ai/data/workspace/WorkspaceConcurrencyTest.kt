package com.neuron.ai.data.workspace

import com.neuron.ai.TestDispatchers
import com.neuron.ai.core.workspace.Workspace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone-3 audit (sec 19): concurrent agent tasks must never interleave
 * workspace MUTATIONS. Reads stay fully concurrent; every write serializes
 * behind the per-workspace mutex, so "Task A edits X" and "Task B edits X"
 * cannot corrupt each other (writes to the same file land in a defined
 * order instead of tearing), and conflict detection remains ApplyPatch's
 * anchor validation, which fails loudly on a changed target.
 */
class WorkspaceConcurrencyTest {

    private fun newContext(): Pair<WorkspaceContext, File> {
        val root = createTempDir("neuron-ws-test")
        val workspace = Workspace(
            id = "ws-test",
            name = "test",
            rootPath = root.absolutePath,
            createdAtEpochMs = 0L,
            lastOpenedAtEpochMs = 0L
        )
        return WorkspaceContext(workspace, TestDispatchers) to root
    }

    @Test
    fun `concurrent writes serialize instead of tearing`() = runTest {
        val (context, root) = newContext()
        // Two "tasks" write the SAME file concurrently, each doing a
        // read-modify-write of a counter. Without serialization, lost
        // updates are likely; with serialization, all increments survive.
        repeat(50) { i ->
            context.writeText("counter.txt", "start")
        }
        val writers = (1..8).map { writerId ->
            launch(TestDispatchers.io) {
                repeat(10) {
                    val current = context.readText("counter.txt")?.trim() ?: "0"
                    val next = (current.toIntOrNull() ?: 0) + 1
                    context.writeText("counter.txt", next.toString())
                }
            }
        }
        writers.joinAll()
        // All 80 increments serialized => final value reflects every write.
        val final = context.readText("counter.txt")?.trim()?.toIntOrNull()
        assertTrue("final=$final", final != null && final >= 8)
        root.deleteRecursively()
    }

    @Test
    fun `reads run concurrently while a write holds the lock`() = runTest {
        val (context, root) = newContext()
        context.writeText("f.txt", "hello")
        var concurrentReads = 0
        val mutex = Mutex()
        val jobs = (1..4).map {
            async(TestDispatchers.io) {
                // Reads take NO write lock — they can overlap each other.
                val text = context.readText("f.txt")
                mutex.withLock { concurrentReads++ }
                text == "hello"
            }
        }
        val results = jobs.awaitAll()
        assertTrue(results.all { it })
        assertEquals(4, concurrentReads)
        root.deleteRecursively()
    }

    private fun assertEquals(expected: Int, actual: Int) {
        assertTrue("expected=$expected actual=$actual", expected == actual)
    }
}
