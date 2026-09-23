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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    val workingDirFlow: Flow<File> = _workingDir.asStateFlow()

    private val _state = MutableStateFlow(TerminalState.IDLE)
    val state: Flow<TerminalState> = _state.asStateFlow()

    private val _output = MutableStateFlow<List<TerminalLine>>(emptyList())
    val output: Flow<List<TerminalLine>> = _output.asStateFlow()

    /** Exit code of the last completed command, if any. */
    private val _lastExitCode = MutableStateFlow<Int?>(null)
    val lastExitCode: Flow<Int?> = _lastExitCode.asStateFlow()

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
        env: Map<String, String> = emptyMap()
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
                val dir = _workingDir.value.takeIf { it.isDirectory } ?: File("/").takeIf { it.canRead() }
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .apply {
                        directory(dir)
                        environment().putAll(env)
                        redirectErrorStream(false)
                    }
                    .start()
                activeProcess = process

                val stdoutJob = launch { drain(process.inputStream, TerminalLine.Stream.STDOUT) }
                val stderrJob = launch { drain(process.errorStream, TerminalLine.Stream.STDERR) }

                val code = process.waitFor()
                stdoutJob.join()
                stderrJob.join()
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
            124
        }
        job.join()
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

    private suspend fun drain(stream: java.io.InputStream, streamKind: TerminalLine.Stream) {
        stream.bufferedReader().useLines { lines ->
            lines.forEach { line -> emit(TerminalLine(id, line, streamKind, System.currentTimeMillis())) }
        }
    }

    private fun emit(line: TerminalLine) {
        _output.value = (_output.value + line).let { if (it.size > outputLimit) it.takeLast(outputLimit) else it }
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

    fun closeAll() {
        sessions.keys.toList().forEach { closeSession(it) }
    }
}
