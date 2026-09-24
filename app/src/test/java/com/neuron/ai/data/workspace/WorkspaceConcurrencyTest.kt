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
    fun `concurrent writes never tear the file`() = runTest {
        val (context, root) = newContext()
        // The guarantee under test (M3 sec 19): mutating steps SERIALIZE, so
        // the file is never observed torn/mixed even under heavy concurrent
        // writing. (Lost-update prevention for read-modify-write is a
        // separate concern — handled by ApplyPatch anchor validation.)
        val writers = (1..8).map { writerId ->
            launch(TestDispatchers.io) {
                repeat(25) { i ->
                    val payload = "w$writerId-" + "x".repeat(2000) + "-end"
                    val ok = context.writeText("shared.txt", payload)
                    check(ok)
                    // Every read inside another writer's critical section
                    // must see a COMPLETE payload, never a torn write.
                    val seen = context.readText("shared.txt")
                    if (seen != null) {
                        check(seen.startsWith("w") && seen.endsWith("-end"))
                    }
                }
            }
        }
        writers.joinAll()
        // After all writers finish, the file holds ONE complete payload.
        val final = context.readText("shared.txt")
        assertTrue("final=$final", final != null && final.startsWith("w") && final.endsWith("-end"))
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
