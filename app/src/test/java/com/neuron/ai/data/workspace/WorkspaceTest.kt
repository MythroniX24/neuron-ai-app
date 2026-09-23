package com.neuron.ai.data.workspace

import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Milestone 2: workspace lifecycle + boundary isolation (path traversal). */
class WorkspaceTest {

    private fun tempFilesDir(): File =
        File(System.getProperty("java.io.tmpdir"), "neuron-ws-test-" + System.nanoTime())
            .apply { mkdirs() }

    private fun TestScope.dispatchers() = object : DispatcherProvider {
        override val main: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    }

    @Test
    fun `create open rename delete lifecycle`() = runTest {
        val manager = WorkspaceManagerImpl(tempFilesDir(), dispatchers())
        val ws = manager.create("My Project")
        assertEquals("My Project", ws.name)
        assertTrue(File(ws.rootPath).isDirectory)

        assertNotNull(manager.open(ws.id))
        manager.rename(ws.id, "Renamed")
        assertEquals("Renamed", manager.get(ws.id)?.name)

        manager.delete(ws.id)
        assertNull(manager.get(ws.id))
        assertFalse(File(ws.rootPath).exists())
    }

    @Test
    fun `workspace roots are isolated under the manager root`() = runTest {
        val filesDir = tempFilesDir()
        val manager = WorkspaceManagerImpl(filesDir, dispatchers())
        val a = manager.create("A")
        val b = manager.create("B")

        assertTrue(a.rootPath.startsWith(File(filesDir, "workspaces").canonicalPath))
        assertTrue(b.rootPath.startsWith(File(filesDir, "workspaces").canonicalPath))
        // Distinct roots.
        assertFalse(a.rootPath == b.rootPath)
    }

    @Test
    fun `context rejects path traversal outside the root`() = runTest {
        val manager = WorkspaceManagerImpl(tempFilesDir(), dispatchers())
        val ws = manager.create("Safe")
        val context = WorkspaceContext(ws, dispatchers())

        assertNull(context.resolve("../outside.txt"))
        assertNull(context.resolve("../../etc/passwd"))
        assertNull(context.resolve("/data/local/tmp/evil"))
        // Inside paths resolve fine.
        assertNotNull(context.resolve("src/Main.kt"))
        assertNotNull(context.resolve("./notes.md"))
        // Canonical symlink-style escape via subdirectory also rejected.
        assertNull(context.resolve("a/../../b"))
    }

    @Test
    fun `write read delete inside workspace`() = runTest {
        val manager = WorkspaceManagerImpl(tempFilesDir(), dispatchers())
        val context = WorkspaceContext(manager.create("RW"), dispatchers())

        assertTrue(context.writeText("src/Main.kt", "fun main() {}\n"))
        assertEquals("fun main() {}\n", context.readText("src/Main.kt"))
        assertTrue(context.mkdirs("docs"))
        assertTrue(context.rename("src/Main.kt", "Main2.kt"))
        assertNull(context.readText("src/Main.kt"))
        assertNotNull(context.readText("src/Main2.kt"))
        assertTrue(context.delete("src"))
        assertNull(context.readText("src/Main2.kt"))
    }

    @Test
    fun `search finds names and contents with extension filter`() = runTest {
        val manager = WorkspaceManagerImpl(tempFilesDir(), dispatchers())
        val context = WorkspaceContext(manager.create("Search"), dispatchers())
        context.writeText("readme.md", "hello world")
        context.writeText("src/App.kt", "class App")
        context.writeText("src/App.java", "public class App {}")

        val all = context.search("app")
        assertEquals(2, all.size) // App.kt + App.java by name

        val kotlinOnly = context.search("app", extensions = listOf("kt"))
        assertEquals(1, kotlinOnly.size)
        assertEquals("src/App.kt", kotlinOnly.single().relativePath)

        val content = context.search("hello world")
        assertEquals("readme.md", content.single().relativePath)
    }
}
