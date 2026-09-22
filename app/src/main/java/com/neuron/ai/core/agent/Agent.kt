package com.neuron.ai.core.agent

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

/** What an agent is trying to achieve right now. */
data class AgentGoal(
    val instruction: String,
    val conversationId: String,
    val allowedTools: Set<String> = emptySet()
)

/** One visible step of agent work, e.g. "✓ Searching web". */
data class AgentActivity(
    val stepId: String,
    val title: String,
    val state: State,
    val detail: String? = null
) {
    enum class State { PENDING, RUNNING, DONE, FAILED }
}

/** Events emitted while an agent runs. */
sealed class AgentEvent {
    data class ActivityStarted(val activity: AgentActivity) : AgentEvent()
    data class ActivityUpdated(val activity: AgentActivity) : AgentEvent()
    data class TextDelta(val text: String) : AgentEvent()
    data class Finished(val summary: String) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
}

/**
 * Owns agent lifecycles: creation, cancellation, and observation.
 * Phase 2 extends this to concurrent, background-capable runs.
 */
interface AgentRuntime {
    fun start(agent: Agent, goal: AgentGoal): String
    fun cancel(runId: String)
    fun activityOf(runId: String): Flow<List<AgentActivity>>
}
