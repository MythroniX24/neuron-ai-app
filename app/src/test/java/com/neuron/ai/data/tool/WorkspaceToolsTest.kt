package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.data.workspace.WorkspaceContext
import com.neuron.ai.data.workspace.WorkspaceManagerImpl
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Milestone 2 tool behavior: workspace binding, gating and safety. */
class WorkspaceToolsTest {

    private class FakeEnv(
        private val workspace: WorkspaceContext?,
        private val terminal: Boolean = false
    ) : WorkspaceToolEnv {
        override suspend fun activeWorkspace(): WorkspaceContext? = workspace
        override suspend fun terminalEnabled(): Boolean = terminal
        override suspend fun terminalSessionKey(): String? = "test-session"
    }

    private fun dispatchers() = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private fun newContext(dir: File): WorkspaceContext =
        WorkspaceContext(
            com.neuron.ai.core.workspace.Workspace(
                id = "ws-test",
                name = "test",
                rootPath = dir.canonicalPath,
                createdAtEpochMs = 0,
                lastOpenedAtEpochMs = 0
            ),
            dispatchers()
        )

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "neuron-tools-test-" + System.nanoTime())
            .apply { mkdirs() }

    @Test
    fun `tools fail cleanly without a workspace`() = runTest {
        val env = FakeEnv(workspace = null)
        val result = WorkspaceTools.ReadFile(env).execute("""{"path":"x.txt"}""")
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("No workspace attached"))
    }

    @Test
    fun `read write edit roundtrip through tools`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(workspace = context)

        assertTrue(
            WorkspaceTools.WriteFile(env).execute(
                """{"path":"src/A.kt","content":"fun a() = 1"}"""
            ) is ToolResult.Success
        )
        val read = WorkspaceTools.ReadFile(env).execute("""{"path":"src/A.kt"}""")
        assertEquals("fun a() = 1", (read as ToolResult.Success).output)

        val edit = CodingTools.EditCode(env).execute(
            """{"path":"src/A.kt","oldText":"fun a() = 1","newText":"fun a() = 2"}"""
        )
        assertTrue(edit is ToolResult.Success)
        assertEquals("fun a() = 2", (WorkspaceTools.ReadFile(env).execute("""{"path":"src/A.kt"}""") as ToolResult.Success).output)
    }

    @Test
    fun `patch rejects when file changed since read - conflict detection`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(workspace = context)
        WorkspaceTools.WriteFile(env).execute("""{"path":"f.txt","content":"original"}""")

        // Simulate an external modification after the agent read the file.
        context.writeText("f.txt", "externally modified")

        val patch = CodingTools.ApplyPatch(env).execute(
            """{"path":"f.txt","expectedText":"original","replacement":"new"}"""
        )
        assertTrue(patch is ToolResult.Failure)
        assertTrue((patch as ToolResult.Failure).message.contains("Conflict"))
    }

    @Test
    fun `terminal tool asks permission when capability disabled - no silent enable`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(workspace = context, terminal = false)
        val manager = com.neuron.ai.data.terminal.TerminalManager(dispatchers())
        val permissions = com.neuron.ai.data.permissions.SessionPermissionManager()
        // Answer the permission popup with DENY: the OFF flow must resolve the
        // request (never hang) and refuse execution.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            val pending = permissions.pendingRequests.first { it.isNotEmpty() }
            permissions.deny(pending.first().id)
        }

        val result = TerminalTool(env, manager, permissions).runCommand("echo hi", 10)
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `terminal tool executes when capability enabled`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(workspace = context, terminal = true)
        val manager = com.neuron.ai.data.terminal.TerminalManager(dispatchers())
        val permissions = com.neuron.ai.data.permissions.SessionPermissionManager()

        val result = TerminalTool(env, manager, permissions).runCommand("echo gated-ok", 15)
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("gated-ok"))
    }

    @Test
    fun `delete tool is DESTRUCTIVE risk`() = runTest {
        val env = FakeEnv(workspace = null)
        assertEquals(RiskLevel.DESTRUCTIVE, WorkspaceTools.Delete(env).riskLevel)
        assertEquals(RiskLevel.ELEVATED, WorkspaceTools.WriteFile(env).riskLevel)
    }

    @Test
    fun `diff engine renders minus and plus lines`() = runTest {
        val diff = DiffEngine.unifiedDiff("f.kt", "a\nb\nc\n", "a\nB\nc\n")
        assertTrue(diff.contains("-b"))
        assertTrue(diff.contains("+B"))
    }
}
