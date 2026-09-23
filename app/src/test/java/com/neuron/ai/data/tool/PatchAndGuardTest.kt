package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.data.workspace.WorkspaceContext
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 2 security regressions: the patch anchor must never be empty or
 * ambiguous, obviously catastrophic terminal commands are refused, and the
 * workspace root is protected from deletion/rename through the context.
 */
class PatchAndGuardTest {

    private fun dispatchers() = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private class FakeEnv(
        private val workspace: WorkspaceContext?,
        private val terminal: Boolean = true
    ) : WorkspaceToolEnv {
        override suspend fun activeWorkspace(): WorkspaceContext? = workspace
        override suspend fun terminalEnabled(): Boolean = terminal
        override suspend fun terminalSessionKey(): String? = "guard-test"
    }

    private fun newContext(dir: File): WorkspaceContext =
        WorkspaceContext(
            com.neuron.ai.core.workspace.Workspace(
                id = "ws-guard",
                name = "guard",
                rootPath = dir.canonicalPath,
                createdAtEpochMs = 0,
                lastOpenedAtEpochMs = 0
            ),
            dispatchers()
        )

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "neuron-guard-test-" + System.nanoTime())
            .apply { mkdirs() }

    @Test
    fun `empty patch anchor is rejected instead of corrupting the file`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(context)
        WorkspaceTools.WriteFile(env).execute("""{"path":"p.txt","content":"valuable"}""")

        val patch = CodingTools.ApplyPatch(env).execute(
            """{"path":"p.txt","expectedText":"","replacement":"EVIL"}"""
        )
        assertTrue(patch is ToolResult.Failure)
        // File untouched.
        val read = WorkspaceTools.ReadFile(env).execute("""{"path":"p.txt"}""")
        assertEquals("valuable", (read as ToolResult.Success).output)
    }

    @Test
    fun `ambiguous patch anchor is rejected`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(context)
        WorkspaceTools.WriteFile(env).execute(
            """{"path":"dup.txt","content":"x target y target z"}"""
        )
        val patch = CodingTools.ApplyPatch(env).execute(
            """{"path":"dup.txt","expectedText":"target","replacement":"T"}"""
        )
        assertTrue(patch is ToolResult.Failure)
        assertTrue((patch as ToolResult.Failure).message.contains("2 places"))
    }

    @Test
    fun `catastrophic commands are refused outright`() {
        assertTrue(CommandGuard.isCatastrophic("rm -rf /"))
        assertTrue(CommandGuard.isCatastrophic("sudo rm -rf /*"))
        assertTrue(CommandGuard.isCatastrophic("mkfs.ext4 /dev/mmcblk0"))
        assertTrue(CommandGuard.isCatastrophic("dd if=/dev/zero of=/dev/block/mmcblk0"))
        assertTrue(CommandGuard.isCatastrophic(":(){ :|:& };:"))
        // Everyday commands must pass the guard.
        assertFalse(CommandGuard.isCatastrophic("rm -rf build/"))
        assertFalse(CommandGuard.isCatastrophic("rm src/old.kt"))
        assertFalse(CommandGuard.isCatastrophic("dd if=a of=b.img"))
        assertFalse(CommandGuard.isCatastrophic("./gradlew assembleDebug"))
    }

    @Test
    fun `workspace root cannot be deleted through the context`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(context)
        // "." resolves to the root itself.
        val result = WorkspaceTools.Delete(env).execute("""{"path":"."}""")
        assertFalse(result is ToolResult.Success && File(context.root.path).exists().not())
        assertTrue(File(context.root.path).exists())
    }

    @Test
    fun `safe patch flow still applies when anchor is unique`() = runTest {
        val context = newContext(tempRoot())
        val env = FakeEnv(context)
        WorkspaceTools.WriteFile(env).execute(
            """{"path":"ok.txt","content":"hello world"}"""
        )
        val patch = CodingTools.ApplyPatch(env).execute(
            """{"path":"ok.txt","expectedText":"hello","replacement":"goodbye"}"""
        )
        assertTrue(patch is ToolResult.Success)
        val read = WorkspaceTools.ReadFile(env).execute("""{"path":"ok.txt"}""")
        assertEquals("goodbye world", (read as ToolResult.Success).output)
    }
}
