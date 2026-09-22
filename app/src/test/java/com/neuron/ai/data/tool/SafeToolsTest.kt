package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Verifies argument handling, permission contracts and workspace sandboxing. */
class SafeToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- No-context tools -----------------------------------------------------------------

    @Test
    fun `current time reports local time`() = runTest {
        val result = SafeTools.CurrentTime().execute("{}")

        val output = (result as ToolResult.Success).output
        assertTrue(output.startsWith("Local time:"))
        assertTrue(output.contains("Unix seconds:"))
    }

    @Test
    fun `calculator evaluates and reports failures as values`() = runTest {
        val tool = SafeTools.Calculator()

        val ok = tool.execute("""{"expression":"(2+3)*4"}""")
        assertEquals("(2+3)*4 = 20", (ok as ToolResult.Success).output)

        val missing = tool.execute("{}")
        assertTrue(missing is ToolResult.Failure)
        assertTrue((missing as ToolResult.Failure).message.contains("expression"))

        val bad = tool.execute("""{"expression":"2 +"}""")
        assertTrue(bad is ToolResult.Failure)
    }

    @Test
    fun `text stats counts words lines and paragraphs`() = runTest {
        val result = SafeTools.TextStats().execute("""{"text":"Hello world\n\nSecond paragraph"}""")

        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Characters:"))
        assertTrue(output.contains("Words: 4"))
        assertTrue(output.contains("Lines: 3"))
        assertTrue(output.contains("Paragraphs: 2"))
    }

    @Test
    fun `safe tools declare no capabilities`() {
        assertTrue(SafeTools.CurrentTime().requiredCapabilities.isEmpty())
        assertTrue(SafeTools.Calculator().requiredCapabilities.isEmpty())
        assertTrue(SafeTools.TextStats().requiredCapabilities.isEmpty())
    }

    // ---- Workspace-sandboxed file tools ------------------------------------------------------

    @Test
    fun `file read returns file contents inside workspace`() = runTest {
        val workspace = tmp.newFolder("workspace")
        File(workspace, "notes/todo.txt").apply { parentFile.mkdirs() }.writeText("buy milk")

        val tool = SafeTools.FileRead(workspace)
        val result = tool.execute("""{"path":"notes/todo.txt"}""")

        assertEquals("buy milk", (result as ToolResult.Success).output)
        assertEquals(setOf(Capability.FILESYSTEM_READ), tool.requiredCapabilities)
    }

    @Test
    fun `file read rejects missing files`() = runTest {
        val tool = SafeTools.FileRead(tmp.newFolder("workspace"))

        val result = tool.execute("""{"path":"does/not/exist.txt"}""")

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("not found"))
    }

    @Test
    fun `file read resolves traversal attempts outside workspace to null`() {
        val workspace = tmp.newFolder("workspace")
        val tool = SafeTools.FileRead(workspace)

        assertNull(tool.resolve("../outside.txt"))
        assertNull(tool.resolve("nested/../../etc/passwd"))

        // A leading slash is treated as relative to the workspace root,
        // so it must still resolve INSIDE the sandbox.
        val absolute = tool.resolve("/absolute/escape")
        assertNotNull(absolute)
        assertTrue(absolute!!.canonicalPath.startsWith(workspace.canonicalPath))

        val valid = tool.resolve("valid/relative.txt")
        assertNotNull(valid)
        assertTrue(valid!!.canonicalPath.startsWith(workspace.canonicalPath))
    }

    @Test
    fun `file read never reads outside the workspace`() = runTest {
        val root = tmp.root
        val workspace = File(root, "workspace").apply { mkdirs() }
        val secret = File(root, "secret.txt").apply { writeText("TOP SECRET") }

        val tool = SafeTools.FileRead(workspace)
        val result = tool.execute("""{"path":"../secret.txt"}""")

        assertTrue("Traversal must be rejected", result is ToolResult.Failure)
        // The secret must never appear in any tool output.
        assertTrue((result as ToolResult.Failure).message.contains("Invalid path"))
        assertEquals("TOP SECRET", secret.readText()) // untouched, obviously
    }

    @Test
    fun `file search finds names and contents`() = runTest {
        val workspace = tmp.newFolder("workspace")
        File(workspace, "report_2024.md").writeText("quarterly revenue numbers")
        File(workspace, "diary.md").writeText("nothing about revenue here")

        val tool = SafeTools.FileSearch(workspace)

        val byName = tool.execute("""{"query":"report"}""")
        val byContent = tool.execute("""{"query":"revenue"}""")

        assertTrue((byName as ToolResult.Success).output.contains("report_2024.md"))
        assertTrue((byContent as ToolResult.Success).output.contains("report_2024.md (content match)"))
        assertEquals(setOf(Capability.FILESYSTEM_READ), tool.requiredCapabilities)
    }

    @Test
    fun `file search reports empty results gracefully`() = runTest {
        val tool = SafeTools.FileSearch(tmp.newFolder("workspace"))

        val result = tool.execute("""{"query":"zzz-nothing"}""")

        assertTrue((result as ToolResult.Success).output.contains("No matches"))
    }

    @Test
    fun `file tools reject blank or missing arguments`() = runTest {
        val read = SafeTools.FileRead(tmp.newFolder("w1"))
        val search = SafeTools.FileSearch(tmp.newFolder("w2"))

        assertTrue(read.execute("{}") is ToolResult.Failure)
        assertTrue(search.execute("""{"query":"  "}""") is ToolResult.Failure)
    }
}
