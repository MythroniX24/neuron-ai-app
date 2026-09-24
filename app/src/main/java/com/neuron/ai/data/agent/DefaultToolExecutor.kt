package com.neuron.ai.data.agent

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.ToolExecutor
import com.neuron.ai.core.agent.ToolRegistry
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.permissions.PermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * Executes tools only after every required capability is granted (risk-aware,
 * workspace-scoped). Adds a timeout budget and bounded retry for transient
 * failures. Denials, unknown tools and crashes are failure values, not
 * exceptions — dangerous operations can never run silently.
 *
 * [onPermissionWait] lets an agent runtime observe permission pauses:
 * called with `true` before the request suspends, `false` after it resolves.
 */
class DefaultToolExecutor(
    private val registry: ToolRegistry,
    private val permissions: PermissionManager,
    private val logger: Logger? = null,
    /** Retries for transient failures (not permission denials, not timeouts). */
    private val maxRetries: Int = 1,
    private val retryDelayMs: Long = 400,
    /** Hard ceiling multiplied with the tool's own timeout preference. */
    private val timeoutCeilingMs: Long = 60_000
) : ToolExecutor {

    override suspend fun execute(toolId: String, argumentsJson: String): ToolResult =
        execute(toolId, argumentsJson, onPermissionWait = null)

    override suspend fun execute(
        toolId: String,
        argumentsJson: String,
        onPermissionWait: (suspend (Boolean) -> Unit)?
    ): ToolResult {
        val tool = registry.find(toolId)
            ?: return ToolResult.Failure("Unknown tool: $toolId")

        for (capability in tool.requiredCapabilities) {
            onPermissionWait?.invoke(true)
            val granted = try {
                permissions.request(
                    capability = capability,
                    reason = "${tool.title} needs this permission.",
                    requestedBy = tool.title,
                    riskLevel = tool.riskLevel
                )
            } finally {
                onPermissionWait?.invoke(false)
            }
            if (!granted) {
                // DENIAL is terminal — never retried, so one refusal never
                // re-surfaces the same dialog within a turn.
                return ToolResult.Denied("Permission denied for ${tool.title}.")
            }
        }

        val timeoutMs = minOf(tool.timeoutMs, timeoutCeilingMs)
        var attempt = 0
        while (true) {
            attempt++
            val result = try {
                withTimeout(timeoutMs) { tool.execute(argumentsJson) }
            } catch (timeout: TimeoutCancellationException) {
                // Rethrow if an OUTER scope was cancelled; otherwise this is
                // this tool's own budget expiring → structured TimedOut value.
                currentCoroutineContext().ensureActive()
                ToolResult.TimedOut(timeoutMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger?.w("Tool", "Tool $toolId failed (attempt $attempt)", t)
                ToolResult.Failure("Tool error: ${t.message ?: "unknown"}")
            }

            val retryable = when (result) {
                is ToolResult.Success -> false
                is ToolResult.TimedOut -> false // timeouts are a budget signal, not transient
                is ToolResult.Denied -> false   // a refusal is never retried
                is ToolResult.Failure -> attempt <= maxRetries
            }
            if (!retryable) return result

            delay(retryDelayMs * attempt)
        }
    }
}
