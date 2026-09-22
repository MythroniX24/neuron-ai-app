package com.neuron.ai.data.agent

import com.neuron.ai.core.agent.ToolExecutor
import com.neuron.ai.core.agent.ToolRegistry
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.log.Logger

/**
 * Executes tools only after every required capability is granted. Requests are
 * surfaced to the user (allow/deny) via [PermissionManager]; denials and
 * unknown tools are returned as failure values — never thrown.
 */
class DefaultToolExecutor(
    private val registry: ToolRegistry,
    private val permissions: PermissionManager,
    private val logger: Logger? = null
) : ToolExecutor {

    override suspend fun execute(toolId: String, argumentsJson: String): ToolResult {
        val tool = registry.find(toolId)
            ?: return ToolResult.Failure("Unknown tool: $toolId")

        for (capability in tool.requiredCapabilities) {
            val granted = permissions.request(
                capability = capability,
                reason = "${tool.title} needs this permission.",
                requestedBy = tool.title
            )
            if (!granted) {
                return ToolResult.Failure("Permission denied for ${tool.title}.")
            }
        }

        return try {
            tool.execute(argumentsJson)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logger?.w("Tool", "Tool $toolId failed", t)
            ToolResult.Failure("Tool error: ${t.message ?: "unknown"}")
        }
    }
}
