package com.neuron.ai.data.terminal

import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Real-process terminal tests. These run actual shell commands, so they use
 * Dispatchers.IO (not the virtual-time scheduler) and assert observable
 * session state after bounded waits.
 */
class TerminalSessionTest {

    private fun realDispatchers() = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private fun newSession(dir: File? = null): TerminalSession =
        TerminalManager(realDispatchers()).sessionFor("test-${System.nanoTime()}", null, dir)

    private suspend fun awaitExit(session: TerminalSession, previous: Int?): Int {
        withTimeout(30_000) {
            while (session.lastExitCode.first() == previous) {
                kotlinx.coroutines.delay(50)
            }
        }
        return session.lastExitCode.first()!!
    }

    @Test
    fun `echo produces stdout and exit 0`() = runTest {
        val session = newSession()
        val code = session.execute("echo neuron-test-42", timeoutMs = 15_000)
        assertEquals(0, code)
        val lines = session.output.first()
        assertTrue(lines.any { it.text == "neuron-test-42" && it.stream == TerminalLine.Stream.STDOUT })
        assertTrue(lines.any { it.text.contains("exit 0") })
        session.close()
    }

    @Test
    fun `stderr is captured with its stream kind`() = runTest {
        val session = newSession()
        val code = session.execute("echo boom 1>&2", timeoutMs = 15_000)
        assertEquals(0, code)
        assertTrue(
            session.output.first().any {
                it.text == "boom" && it.stream == TerminalLine.Stream.STDERR
            }
        )
        session.close()
    }

    @Test
    fun `failing command reports nonzero exit code`() = runTest {
        val session = newSession()
        val code = session.execute("exit 7", timeoutMs = 15_000)
        assertEquals(7, code)
        session.close()
    }

    @Test
    fun `working directory is honored`() = runTest {
        val dir = File(System.getProperty("java.io.tmpdir"))
        val session = newSession(dir)
        session.execute("pwd", timeoutMs = 15_000)
        val lines = session.output.first().filter { it.stream == TerminalLine.Stream.STDOUT }
        assertTrue(lines.any { it.text == dir.canonicalPath })
        session.close()
    }

    @Test
    fun `timeout kills the process with exit 124`() = runTest {
        val session = newSession()
        val code = session.execute("sleep 30", timeoutMs = 1_500)
        assertEquals(124, code)
        session.close()
    }

    @Test
    fun `stop cancels a running command`() = runTest {
        val session = newSession()
        val runner = launch { session.execute("sleep 30", timeoutMs = 60_000) }
        // Real-time wait: let the process actually start before stopping.
        kotlinx.coroutines.withContext(Dispatchers.IO) { delay(1_000) }
        session.stop()
        // Real-time poll: the cancelled job settles the session back to IDLE.
        kotlinx.coroutines.withTimeout(10_000) {
            while (session.state.first() != TerminalState.IDLE) {
                kotlinx.coroutines.withContext(Dispatchers.IO) { delay(100) }
            }
        }
        assertEquals(TerminalState.IDLE, session.state.first())
        runner.cancel()
        session.close()
    }

    @Test
    fun `history records executed commands`() = runTest {
        val session = newSession()
        session.execute("echo one", timeoutMs = 15_000)
        session.execute("echo two", timeoutMs = 15_000)
        assertTrue(session.historySnapshot().containsAll(listOf("echo one", "echo two")))
        session.close()
    }
}
