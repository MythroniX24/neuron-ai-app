package com.neuron.ai.data.terminal

import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One line of terminal output with its stream origin. */
data class TerminalLine(
    val sessionId: String,
    val text: String,
    val stream: Stream,
    val atEpochMs: Long
) {
    enum class Stream { STDOUT, STDERR, SYSTEM }

    companion object {
        fun system(sessionId: String, text: String) =
            TerminalLine(sessionId, text, Stream.SYSTEM, System.currentTimeMillis())
    }
}

/** Lifecycle state of a terminal session's current/last process. */
enum class TerminalState { IDLE, RUNNING, STOPPING, CLOSED }

/**
 * One real shell session. Commands run through `/system/bin/sh -c` on a
 * dedicated process; output streams line-by-line to a shared buffer.
 * User terminal UI and AI terminal tools share this class — no second stack.
 */
class TerminalSession internal constructor(
    val id: String,
    val workspaceId: String?,
    workingDir: File?,
    private val scope: CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
    outputBufferLimit: Int = 2_000
) {
    private val _workingDir = MutableStateFlow(workingDir ?: File("/"))
    val workingDirFlow: kotlinx.coroutines.flow.StateFlow<File> = _workingDir.asStateFlow()

    private val _state = MutableStateFlow(TerminalState.IDLE)
    val state: kotlinx.coroutines.flow.StateFlow<TerminalState> = _state.asStateFlow()

    private val _output = MutableStateFlow<List<TerminalLine>>(emptyList())
    val output: kotlinx.coroutines.flow.StateFlow<List<TerminalLine>> = _output.asStateFlow()

    /** Exit code of the last completed command, if any. */
    private val _lastExitCode = MutableStateFlow<Int?>(null)
    val lastExitCode: kotlinx.coroutines.flow.StateFlow<Int?> = _lastExitCode.asStateFlow()

    private var outputLimit = outputBufferLimit
    private val history = mutableListOf<String>()
    private var activeProcess: Process? = null
    private var activeJob: Job? = null

    /** Appends a command to history (user UI and AI share it). */
    fun recordCommand(command: String) {
        if (command.isNotBlank()) history.add(command)
    }

    fun historySnapshot(): List<String> = history.toList()

    fun clearOutput() {
        _output.value = emptyList()
    }

    fun setOutputLimit(limit: Int) {
        outputLimit = limit
        trim()
    }

    /**
     * Executes [command] with a timeout; streams stdout/stderr into the
     * buffer and returns the exit code. Cancellation kills the process.
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = 120_000,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null
    ): Int = withContext(io) {
        if (_state.value == TerminalState.RUNNING) {
            emit(TerminalLine.system(id, "A command is already running in this session."))
            return@withContext -1
        }
        recordCommand(command)
        _state.value = TerminalState.RUNNING
        emit(TerminalLine.system(id, "$ $command"))
        val started = System.currentTimeMillis()
        val exitDeferred = CompletableDeferred<Int>()
        val job = scope.launch {
            try {
                // Per-command override (AI tools pin the workspace root);
                // falls back to the session's working directory (user panel).
                val dir = workingDir?.takeIf { it.isDirectory }
                    ?: _workingDir.value.takeIf { it.isDirectory }
                    ?: File("/").takeIf { it.canRead() }
                val process = ProcessBuilder(shellPath(), "-c", command)
                    .apply {
                        directory(dir)
                        environment().putAll(env)
                        redirectErrorStream(false)
                    }
                    .start()
                activeProcess = process

                // Daemon threads, NOT coroutines: a killed `sh -c "sleep 30"` leaves
            // the orphaned `sleep` holding our stdout/stderr pipes open for its
            // full duration. Waiting for stream EOF would hang execution; daemon
            // readers take everything available and die silently afterwards
            // (trailing output after a kill may be truncated — acceptable).
            val stdoutThread = drainThread(process.inputStream, TerminalLine.Stream.STDOUT)
            val stderrThread = drainThread(process.errorStream, TerminalLine.Stream.STDERR)
            stdoutThread.start()
            stderrThread.start()

            val code = process.waitFor()
            // Normal exit: brief, BOUNDED flush window (EOF arrives as soon as
            // every writer dies — this only guards the last lines).
            stdoutThread.join(2_000)
            stderrThread.join(2_000)
            exitDeferred.complete(code)
            } catch (cancelled: CancellationException) {
                activeProcess?.destroyForcibly()
                emit(TerminalLine.system(id, "^C stopped"))
                exitDeferred.complete(130)
                throw cancelled
            } catch (t: Throwable) {
                emit(TerminalLine.system(id, "shell error: ${t.message ?: "unknown"}"))
                exitDeferred.complete(127)
            }
        }
        activeJob = job

        val exitCode = try {
            withTimeout(timeoutMs) { exitDeferred.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // Rethrow only if the OUTER scope was cancelled (user stop / AI cancel);
            // otherwise this is the command's own timeout budget.
            currentCoroutineContext().ensureActive()
            activeProcess?.destroyForcibly()
            emit(TerminalLine.system(id, "timed out after ${timeoutMs / 1000}s"))
            // Same settle semantics as stop(): the job must not linger on
            // orphaned pipe readers after a timeout kill.
            job.cancel()
            124
        }
        // stop()/cancel paths settle via CancellationException; never block
        // unboundedly on the job here.
        withTimeoutOrNull(2_000) { job.join() }
        _lastExitCode.value = exitCode
        _state.value = TerminalState.IDLE
        val elapsed = System.currentTimeMillis() - started
        emit(TerminalLine.system(id, "[exit $exitCode · ${elapsed}ms]"))
        exitCode
    }

    /** Best-effort stop of the running command (user Stop / AI cancel). */
    fun stop() {
        activeJob?.cancel()
        activeProcess?.destroyForcibly()
    }

    /** Changes the working directory if it exists (workspace-validated upstream). */
    suspend fun changeDir(path: File): Boolean = withContext(io) {
        if (path.isDirectory) {
            _workingDir.value = path.canonicalFile
            emit(TerminalLine.system(id, path.canonicalPath))
            true
        } else {
            false
        }
    }

    internal fun close() {
        stop()
        _state.value = TerminalState.CLOSED
    }

    companion object {
        /** Android ships /system/bin/sh; JVM/desktop environments use /bin/sh. */
        internal fun shellPath(): String =
            if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }

    private fun drainThread(stream: java.io.InputStream, streamKind: TerminalLine.Stream): Thread =
        Thread {
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        emit(TerminalLine(id, line, streamKind, System.currentTimeMillis()))
                    }
                }
            } catch (_: Exception) {
                // Pipe broken by a process kill — nothing more to read.
            }
        }.apply { isDaemon = true }

    private fun emit(line: TerminalLine) {
        // Atomic CAS update — drain threads and the executor coroutine emit concurrently.
        _output.update { list ->
            val next = list + line
            if (next.size > outputLimit) next.takeLast(outputLimit) else next
        }
    }

    private fun trim() {
        _output.value = _output.value.takeLast(outputLimit)
    }
}

/**
 * Owns terminal sessions keyed by workspace/conversation context. The user
 * panel and AI tools resolve the SAME session per conversation — one shared
 * process infrastructure, no duplicate stacks.
 */
class TerminalManager(private val dispatchers: DispatcherProvider) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val io = dispatchers.io
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /** Returns (creating if needed) the session bound to [contextKey]. */
    fun sessionFor(contextKey: String, workspaceId: String?, workingDir: File?): TerminalSession =
        sessions.getOrPut(contextKey) {
            TerminalSession(
                id = "term-" + UUID.randomUUID().toString().take(8),
                workspaceId = workspaceId,
                workingDir = workingDir,
                scope = scope,
                io = io
            )
        }

    fun closeSession(contextKey: String) {
        sessions.remove(contextKey)?.close()
    }

    /** True while any session in [contextKey] has a live process. */
    fun hasRunningProcess(contextKey: String): Boolean {
        val session = sessions[contextKey] ?: return false
        val state = session.state.value
        return state == TerminalState.RUNNING || state == TerminalState.STOPPING
    }

    /** Closes a session only when idle; returns false if a process is live. */
    fun closeIfIdle(contextKey: String): Boolean {
        val session = sessions[contextKey] ?: return true
        if (hasRunningProcess(contextKey)) return false
        closeSession(contextKey)
        return true
    }

    fun closeAll() {
        sessions.keys.toList().forEach { closeSession(it) }
    }
}
