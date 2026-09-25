package com.neuron.ai.core.agent

import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.provider.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * An agent pursues a goal using tools and an AI provider.
 * Implementations report progress via [activity]; they must never expose
 * private chain-of-thought — only user-meaningful step titles.
 */
interface Agent {
    val id: String
    val displayName: String

    fun run(goal: AgentGoal): Flow<AgentEvent>
}

/**
 * Which tools an agent run may use. `All` exposes every registered tool;
 * `Only` is a strict allowlist (used by future agent modes).
 */
sealed class ToolPolicy {
    data object All : ToolPolicy()
    data class Only(val toolIds: Set<String>) : ToolPolicy()
}

/** What an agent is trying to achieve right now. */
data class AgentGoal(
    val instruction: String,
    val conversationId: String,
    /**
     * Attachments of the CURRENT user message. Images go to the model as
     * vision input; text-like files must be flattened into [instruction]
     * by the caller before the goal is built.
     */
    val attachments: List<com.neuron.ai.core.conversation.Attachment> = emptyList(),
    /** Deprecated Phase 1 field kept for source compatibility; use [toolPolicy]. */
    val allowedTools: Set<String> = emptySet(),
    /** Prior conversation turns (oldest first) so multi-turn chat has context. */
    val history: List<ChatMessage> = emptyList(),
    /** Optional extra guidance (workspace path, agent mode, …). */
    val systemPromptSuffix: String? = null,
    val toolPolicy: ToolPolicy = if (allowedTools.isEmpty()) ToolPolicy.All else ToolPolicy.Only(allowedTools)
) {
    /** Resolves the effective allowlist given every registered tool id. */
    fun allowedToolIds(registeredIds: Set<String>): Set<String> = when (val policy = toolPolicy) {
        is ToolPolicy.All -> registeredIds
        is ToolPolicy.Only -> policy.toolIds intersect registeredIds
    }
}

/** One visible step of agent work, e.g. "✓ Searching web". */
data class AgentActivity(
    val stepId: String,
    val title: String,
    val state: State,
    val detail: String? = null,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long? = null
) {
    enum class State { PENDING, RUNNING, WAITING_FOR_PERMISSION, DONE, FAILED }
}

/** Events emitted while an agent runs. */
sealed class AgentEvent {
    data class ActivityStarted(val activity: AgentActivity) : AgentEvent()
    data class ActivityUpdated(val activity: AgentActivity) : AgentEvent()

    /** The agent paused mid-step waiting for a user permission decision. */
    data class PermissionRequested(val requestId: String, val title: String) : AgentEvent()

    /** The user's permission decision let the run continue. */
    data object PermissionResolved : AgentEvent()

    data class TextDelta(val text: String) : AgentEvent()
    data class Finished(val summary: String) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
}

/**
 * Observable state of one agent execution. Kept as plain data so the runtime
 * can persist/restore or mirror it onto the task system without coupling.
 */
data class AgentRun(
    val runId: String,
    val agentId: String,
    val conversationId: String,
    val instruction: String,
    val state: State,
    val activities: List<AgentActivity> = emptyList(),
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val error: String? = null
) {
    enum class State { QUEUED, RUNNING, WAITING_FOR_PERMISSION, COMPLETED, FAILED, CANCELLED }
}

/** Handle for one launched agent execution. */
interface AgentSession {
    val runId: String
    val run: Flow<AgentRun>
    fun cancel()
}

/**
 * Owns agent lifecycles: launch, observe, cancel. Runs are independent of any
 * UI scope, so they survive configuration changes and can be re-observed from
 * a new screen (task screen, chat screen…).
 */
interface AgentRuntime {
    fun launch(agent: Agent, goal: AgentGoal): AgentSession
    fun cancel(runId: String)
    fun runOf(runId: String): Flow<AgentRun>
    val activeRuns: Flow<List<AgentRun>>
}
