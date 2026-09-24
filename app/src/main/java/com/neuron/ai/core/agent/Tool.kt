package com.neuron.ai.core.agent

import com.neuron.ai.core.permissions.Capability
import kotlinx.coroutines.flow.Flow

/**
 * How risky a tool is. DRIVING the permission defaults:
 * SAFE tools may be auto-granted, ELEVATED asks once per session,
 * DESTRUCTIVE always asks the user — every single time.
 */
enum class RiskLevel { SAFE, ELEVATED, DESTRUCTIVE }

/**
 * A capability an agent can invoke: web search, file read, shell command…
 * Tools own their execution logic; [ToolExecutor] orchestrates permission
 * checks, timeouts and retries around [execute].
 */
interface Tool {
    /** Stable identifier the model references, e.g. "web.search". */
    val id: String

    /** Short human title for the agent activity UI ("Searching web"). */
    val title: String

    /** One-line explanation for the model and for documentation. */
    val description: String

    /** Capabilities that must be granted before execution is allowed. */
    val requiredCapabilities: Set<Capability>

    /** JSON-Schema-like description of the arguments. */
    val parametersSchemaJson: String

    /** JSON-Schema-like description of the successful output. */
    val outputSchemaJson: String get() = "{}"

    val riskLevel: RiskLevel get() = RiskLevel.SAFE

    /** Default timeout applied by the executor when the tool itself doesn't. */
    val timeoutMs: Long get() = 15_000

    /** Runs the tool. Implementations must parse defensively and never throw. */
    suspend fun execute(argumentsJson: String): ToolResult

    /**
     * Best-effort cooperative cancel for long-running work (streams, loops).
     * The executor's timeout/cancellation handles the rest.
     */
    fun cancel() {}
}

/** Outcome of a tool execution; errors are values, not exceptions. */
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Failure(val message: String) : ToolResult()

    /** Execution exceeded its budget — a distinct, retryable failure. */
    data class TimedOut(val timeoutMs: Long) : ToolResult() {
        val message: String get() = "Timed out after ${timeoutMs / 1000}s."
    }

    /**
     * A user or policy REFUSAL (permission denied, action not approved).
     * Unlike [Failure] this is TERMINAL: executors must not retry it —
     * retrying would nag the user with repeated dialogs for one refusal.
     */
    data class Denied(val message: String) : ToolResult()
}

/**
 * Executes a registered tool after enforcing its permission requirements.
 * Returns failures as values; dangerous operations never run silently.
 *
 * The 3-arg variant reports permission pauses so runtimes can surface
 * WAITING_FOR_PERMISSION state; the 2-arg form is a plain execution.
 */
interface ToolExecutor {
    suspend fun execute(toolId: String, argumentsJson: String): ToolResult

    suspend fun execute(
        toolId: String,
        argumentsJson: String,
        onPermissionWait: (suspend (Boolean) -> Unit)?
    ): ToolResult = execute(toolId, argumentsJson)
}

/** Observable registry of tools available to agents. */
interface ToolRegistry {
    val tools: Flow<List<Tool>>
    suspend fun register(tool: Tool)
    suspend fun unregister(toolId: String)
    suspend fun find(toolId: String): Tool?
}

/**
 * Declares which tools an agent is allowed to use in a given context.
 * The default exposes every SAFE tool — ELEVATED and DESTRUCTIVE tools are
 * opt-in, never default-on.
 */
fun interface ToolSelector {
    fun select(allTools: List<Tool>): List<Tool>

    companion object {
        /** Everything SAFE plus any explicitly listed tool. */
        fun default(): ToolSelector = ToolSelector { tools ->
            tools.filter { it.riskLevel == RiskLevel.SAFE }
        }
    }
}
