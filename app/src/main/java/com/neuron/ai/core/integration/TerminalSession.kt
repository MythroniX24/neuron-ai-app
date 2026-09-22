package com.neuron.ai.core.integration

import kotlinx.coroutines.flow.Flow

/**
 * Terminal integration for the coding agent.
 * A Phase 2 implementation can host a local shell; commands are always
 * subject to the permission system before execution.
 */
interface TerminalSession {
    /** Starts the session if not already running. */
    suspend fun start()

    /** Executes a command; output lines arrive on [output] as they stream. */
    fun execute(command: String)

    /** Combined stdout/stderr stream. */
    val output: Flow<Line>

    /** Current exit code of the last finished command, if any. */
    val lastExitCode: Int?

    fun close()

    data class Line(val text: String, val isError: Boolean = false)
}
