package com.neuron.ai.data.agent

import com.neuron.ai.core.agent.AgentEvent
import com.neuron.ai.core.agent.AgentGoal
import com.neuron.ai.core.agent.AgentActivity
import com.neuron.ai.core.agent.Agent
import com.neuron.ai.core.agent.ToolExecutor
import com.neuron.ai.core.agent.ToolRegistry
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.StreamEvent
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.ToolSpec
import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json

/**
 * Phase 1 agent loop:
 *
 *   user request → model → (tool decision → tool → result → model)* → final answer
 *
 * The loop runs while the model keeps requesting tools and [maxSteps] is not
 * exhausted. Only user-meaningful activity titles are emitted — never raw
 * model reasoning.
 */
class ToolUsingAgent(
    private val provider: AIProvider,
    private val model: Model,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val logger: Logger? = null,
    private val maxSteps: Int = 6
) : Agent {

    override val id: String = "agent.tool-using"
    override val displayName: String = "Neuron Agent"

    override fun run(goal: AgentGoal): Flow<AgentEvent> = channelFlow {
        val history = mutableListOf<ChatMessage>(
            ChatMessage(role = ChatMessage.Role.USER, content = goal.instruction)
        )

        var step = 0
        while (step < maxSteps) {
            step++

            // Strict allowlist: an empty set means tools are disabled for this run.
            val availableTools = toolRegistry.tools.first()
                .filter { it.id in goal.allowedTools }

            val request = CompletionRequest(
                model = model,
                messages = history.toList(),
                tools = availableTools.map {
                    ToolSpec(
                        id = it.id,
                        description = it.description,
                        parametersSchemaJson = it.parametersSchemaJson
                    )
                }
            )

            // One model turn, streamed.
            var assistantText = StringBuilder()
            val toolCalls = mutableListOf<com.neuron.ai.core.provider.ProposedToolCall>()
            var streamError: NeuronError? = null
            var completed = false

            val stream = provider.stream(request)
                .onStart { send(AgentEvent.ActivityStarted(understanding(step))) }
            stream.collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        assistantText.append(event.text)
                        send(AgentEvent.TextDelta(event.text))
                    }

                    is StreamEvent.ToolCallRequested -> {
                        toolCalls += com.neuron.ai.core.provider.ProposedToolCall(
                            callId = event.callId,
                            toolId = event.toolId,
                            argumentsJson = event.argumentsJson
                        )
                    }

                    is StreamEvent.Failed -> streamError = event.error
                    StreamEvent.Completed -> completed = true
                }
            }

            streamError?.let { error ->
                send(AgentEvent.Failed(error.userMessage))
                return@channelFlow
            }

            if (toolCalls.isEmpty()) {
                send(AgentEvent.Finished(assistantText.toString()))
                return@channelFlow
            }

            // Record the assistant's tool request, then run each tool.
            history += ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = assistantText.toString(),
                toolCalls = toolCalls.toList()
            )

            for (call in toolCalls) {
                val tool = toolRegistry.find(call.toolId)
                val activity = AgentActivity(
                    stepId = call.callId,
                    title = tool?.title ?: call.toolId,
                    state = AgentActivity.State.RUNNING
                )
                send(AgentEvent.ActivityStarted(activity))

                val result = if (tool == null) {
                    com.neuron.ai.core.agent.ToolResult.Failure("Unknown tool: ${call.toolId}")
                } else {
                    try {
                        toolExecutor.execute(call.toolId, call.argumentsJson)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        logger?.w("Agent", "Tool ${call.toolId} crashed", t)
                        com.neuron.ai.core.agent.ToolResult.Failure("Tool error: ${t.message}")
                    }
                }

                val finalState = when (result) {
                    is com.neuron.ai.core.agent.ToolResult.Success -> AgentActivity.State.DONE
                    is com.neuron.ai.core.agent.ToolResult.Failure -> AgentActivity.State.FAILED
                }
                val detail = when (result) {
                    is com.neuron.ai.core.agent.ToolResult.Success -> result.output.take(4_000)
                    is com.neuron.ai.core.agent.ToolResult.Failure -> result.message
                }
                send(AgentEvent.ActivityUpdated(activity.copy(state = finalState, detail = detail)))

                history += ChatMessage(
                    role = ChatMessage.Role.TOOL,
                    content = when (result) {
                        is com.neuron.ai.core.agent.ToolResult.Success -> result.output
                        is com.neuron.ai.core.agent.ToolResult.Failure -> "Error: ${result.message}"
                    },
                    toolCallId = call.callId
                )
            }
            // Loop continues: the model now sees the tool results.
        }

        send(AgentEvent.Failed("The agent needed too many steps for this request."))
    }.catch { t ->
        if (t !is kotlinx.coroutines.CancellationException) {
            logger?.e("Agent", "Agent run failed", t)
            emit(AgentEvent.Failed(t.message ?: "Unexpected agent failure."))
        }
    }

    private fun understanding(step: Int) = AgentActivity(
        stepId = "think-$step",
        title = if (step == 1) "Understanding request" else "Continuing",
        state = AgentActivity.State.RUNNING
    )
}
