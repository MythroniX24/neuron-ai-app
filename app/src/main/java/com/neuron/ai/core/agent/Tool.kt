package com.neuron.ai.core.agent

import kotlinx.coroutines.flow.Flow

/**
 * A capability an agent can invoke: web search, file read, shell command…
 * Tools are pure contracts; execution goes through a [ToolExecutor].
 */
interface Tool {
    /** Stable identifier the model references, e.g. "web.search". */
    val id: String

    /** Short human title for the agent activity UI ("Searching web"). */
    val title: String

    /** One-line explanation for the model and for documentation. */
    val description: String

    /** Capabilities that must be granted before execution is allowed. */
    val requiredCapabilities: Set<com.neuron.ai.core.permissions.Capability>

    /** JSON-Schema-like description of the arguments. */
    val parametersSchemaJson: String
}

/** Outcome of a tool execution; errors are values, not exceptions. */
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Failure(val message: String) : ToolResult()
}

/** Executes a registered tool. Implementations enforce permissions. */
interface ToolExecutor {
    suspend fun execute(toolId: String, argumentsJson: String): ToolResult
}

/** Observable registry of tools available to agents. */
interface ToolRegistry {
    val tools: Flow<List<Tool>>
    suspend fun register(tool: Tool)
    suspend fun unregister(toolId: String)
    suspend fun find(toolId: String): Tool?
}
