package com.neuron.ai.data.agent

import com.neuron.ai.core.agent.Agent
import com.neuron.ai.core.agent.AgentEvent
import com.neuron.ai.core.agent.AgentGoal
import com.neuron.ai.core.agent.AgentRun
import com.neuron.ai.core.agent.AgentSession
import com.neuron.ai.core.agent.AgentRuntime
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns agent lifecycles independent of any UI scope. Runs are tracked as
 * [AgentRun] state machines; cancelling a session cancels its coroutine.
 * WAITING_FOR_PERMISSION mirrors the agent's permission pauses so the task
 * system can show the true state.
 */
class DefaultAgentRuntime(
    dispatchers: DispatcherProvider
) : AgentRuntime {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val mutex = Mutex()

    private val _runs = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    override val activeRuns: Flow<List<AgentRun>> =
        _runs.map { map -> map.values.filter { !it.state.isTerminal }.sortedBy { it.startedAtEpochMs } }

    private val jobs = ConcurrentHashMap<String, Job>()

    override fun launch(agent: Agent, goal: AgentGoal): AgentSession {
        val runId = "run-" + UUID.randomUUID().toString().take(8)
        val run = AgentRun(
            runId = runId,
            agentId = agent.id,
            conversationId = goal.conversationId,
            instruction = goal.instruction,
            state = AgentRun.State.RUNNING,
            startedAtEpochMs = System.currentTimeMillis()
        )
        scope.launch { mutex.withLock { _runs.value = _runs.value + (runId to run) } }

        val job = scope.launch {
            try {
                agent.run(goal).collect { event ->
                    when (event) {
                        is AgentEvent.ActivityStarted ->
                            upsertActivity(runId, event.activity.stepId) { event.activity }
                        is AgentEvent.ActivityUpdated ->
                            upsertActivity(runId, event.activity.stepId) { event.activity }
                        is AgentEvent.PermissionRequested ->
                            update(runId) { it.copy(state = AgentRun.State.WAITING_FOR_PERMISSION) }
                        is AgentEvent.PermissionResolved ->
                            update(runId) { it.copy(state = AgentRun.State.RUNNING) }
                        is AgentEvent.Failed ->
                            update(runId) {
                                it.copy(state = AgentRun.State.FAILED, error = event.message, finishedAtEpochMs = System.currentTimeMillis())
                            }
                        is AgentEvent.TextDelta, is AgentEvent.Finished -> Unit
                    }
                }
                update(runId) {
                    if (!it.state.isTerminal) {
                        it.copy(state = AgentRun.State.COMPLETED, finishedAtEpochMs = System.currentTimeMillis())
                    } else {
                        it
                    }
                }
            } catch (cancelled: CancellationException) {
                update(runId) {
                    it.copy(state = AgentRun.State.CANCELLED, finishedAtEpochMs = System.currentTimeMillis())
                }
                throw cancelled
            } catch (t: Throwable) {
                update(runId) {
                    it.copy(
                        state = AgentRun.State.FAILED,
                        error = t.message ?: "Unexpected failure",
                        finishedAtEpochMs = System.currentTimeMillis()
                    )
                }
            }
        }
        jobs[runId] = job
        return object : AgentSession {
            override val runId: String = runId
            override val run: Flow<AgentRun> = runOf(runId)
            override fun cancel() = this@DefaultAgentRuntime.cancel(runId)
        }
    }

    override fun cancel(runId: String) {
        jobs.remove(runId)?.cancel()
    }

    override fun runOf(runId: String): Flow<AgentRun> =
        _runs.map { it[runId] ?: stubRun(runId) }

    private fun stubRun(runId: String) = AgentRun(
        runId = runId,
        agentId = "?",
        conversationId = "",
        instruction = "",
        state = AgentRun.State.QUEUED,
        startedAtEpochMs = 0
    )

    private suspend fun upsertActivity(
        runId: String,
        stepId: String,
        activity: () -> com.neuron.ai.core.agent.AgentActivity
    ) = update(runId) { run ->
        val step = activity()
        val existing = run.activities.firstOrNull { it.stepId == stepId }
        val activities = if (existing == null) {
            run.activities + step
        } else {
            run.activities.map { if (it.stepId == stepId) step else it }
        }
        run.copy(activities = activities)
    }

    private suspend fun update(runId: String, transform: (AgentRun) -> AgentRun) =
        mutex.withLock {
            _runs.value[runId]?.let { run ->
                _runs.value = _runs.value + (runId to transform(run))
            }
        }
}

private val AgentRun.State.isTerminal
    get() = this == AgentRun.State.COMPLETED || this == AgentRun.State.FAILED || this == AgentRun.State.CANCELLED
