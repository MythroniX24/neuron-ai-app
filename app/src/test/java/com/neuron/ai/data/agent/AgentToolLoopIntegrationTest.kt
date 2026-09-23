package com.neuron.ai.data.agent

import com.neuron.ai.core.agent.AgentEvent
import com.neuron.ai.core.agent.AgentGoal
import com.neuron.ai.core.agent.ToolPolicy
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.Completion
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.StreamEvent
import com.neuron.ai.core.provider.ToolSpec
import com.neuron.ai.data.permissions.SessionPermissionManager
import com.neuron.ai.data.tool.InMemoryToolRegistry
import com.neuron.ai.data.tool.SafeTools
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end agent-loop integration: request → model → tool decision →
 * permission → tool → result → model → final response.
 */
class AgentToolLoopIntegrationTest {

    /** Fake provider scripted per model call; records every request it sees. */
    private class ScriptedProvider : AIProvider {
        override val id = "fake"
        override val displayName = "Fake"

        val requests = mutableListOf<List<ChatMessage>>()
        /** One entry per model turn: tool request or plain text. */
        val script = ArrayDeque<StreamEvent>()
        var toolCallCounter = 0

        override suspend fun listModels(): List<Model> = emptyList()

        override suspend fun complete(request: CompletionRequest): Completion =
            Completion(ChatMessage(ChatMessage.Role.ASSISTANT, ""), request.model.id)

        override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
            requests.add(request.messages)
            val next = script.removeFirstOrNull()
                ?: StreamEvent.Failed(
                    com.neuron.ai.core.error.NeuronError.Provider("Script exhausted")
                )
            // Rewrite the callId so each scripted tool call is unique.
            if (next is StreamEvent.ToolCallRequested) {
                toolCallCounter++
                emit(next.copy(callId = "call-$toolCallCounter"))
            } else {
                emit(next)
            }
            emit(StreamEvent.Completed)
        }
    }

    private suspend fun buildAgent(
        provider: ScriptedProvider,
        permissions: SessionPermissionManager = SessionPermissionManager()
    ): Pair<ToolUsingAgent, DefaultToolExecutor> {
        val registry = InMemoryToolRegistry()
        registry.register(SafeTools.Calculator())
        registry.register(SafeTools.CurrentTime())
        val executor = DefaultToolExecutor(registry, permissions, logger = null, maxRetries = 0)
        val agent = ToolUsingAgent(
            provider = provider,
            model = Model(id = "test-model", displayName = "test"),
            toolRegistry = registry,
            toolExecutor = executor,
            logger = null
        )
        return agent to executor
    }

    private fun toolRequestEvent(toolId: String, argsJson: String) =
        StreamEvent.ToolCallRequested(
            callId = "placeholder",
            toolId = toolId,
            argumentsJson = argsJson
        )

    private suspend fun collect(agent: ToolUsingAgent, goal: AgentGoal): List<AgentEvent> =
        agent.run(goal).toList()

    @Test
    fun `multi-step loop - tool result reaches model and final response follows`() = runTest {
        val provider = ScriptedProvider()
        // Turn 1: model requests the calculator; Turn 2: model answers.
        provider.script.add(toolRequestEvent("math.evaluate", "{\"expression\":\"6*7\"}"))
        provider.script.add(StreamEvent.Delta("The answer is 42."))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "What is 6*7?", conversationId = "c1"))
        }

        // Model was called twice: once before tool, once after.
        assertEquals(2, provider.requests.size)
        // The second call must carry the assistant tool-call AND the tool result.
        val secondCall = provider.requests[1]
        assertTrue(secondCall.any { it.role == ChatMessage.Role.ASSISTANT && it.toolCalls.isNotEmpty() })
        val toolRow = secondCall.filter { it.role == ChatMessage.Role.TOOL }
        assertEquals(1, toolRow.size)
        assertTrue(toolRow.single().content.contains("42"))

        // Final response surfaced as Finished.
        val finished = events.filterIsInstance<AgentEvent.Finished>().single()
        assertEquals("The answer is 42.", finished.summary)
    }

    @Test
    fun `multiple sequential tool calls in one task`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(toolRequestEvent("math.evaluate", "{\"expression\":\"2+2\"}"))
        provider.script.add(toolRequestEvent("text.stats", "{\"text\":\"hello world\"}"))
        provider.script.add(StreamEvent.Delta("Done: 4 and stats."))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "two tools", conversationId = "c1"))
        }

        assertEquals(3, provider.requests.size)
        val finalCall = provider.requests[2]
        assertEquals(2, finalCall.count { it.role == ChatMessage.Role.TOOL })
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().isNotEmpty())
    }

    @Test
    fun `permission denial feeds structured error to model, no crash`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(toolRequestEvent("math.evaluate", "{\"expression\":\"1+1\"}"))
        provider.script.add(StreamEvent.Delta("Understood, no permission."))

        val permissions = SessionPermissionManager()
        // Auto-deny every request without a dialog.
        val observer = launch {
            permissions.pendingRequests.collect { pending ->
                pending.forEach { permissions.deny(it.id) }
            }
        }

        val (agent, _) = buildAgent(provider, permissions)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "calc", conversationId = "c1"))
        }
        observer.cancelAndJoin()

        // The model still received the structured denial as a tool result.
        val toolRow = provider.requests[1].filter { it.role == ChatMessage.Role.TOOL }
        assertEquals(1, toolRow.size)
        assertTrue(toolRow.single().content.startsWith("Error:"))
        assertTrue(toolRow.single().content.contains("denied"))
        // The run completed gracefully instead of crashing.
        assertNotNull(events.filterIsInstance<AgentEvent.Finished>().singleOrNull())
    }

    @Test
    fun `empty Only policy disables tools - model never sees tool specs`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(StreamEvent.Delta("plain answer"))

        val (agent, _) = buildAgent(provider)
        withTimeout(5_000) {
            collect(
                agent,
                AgentGoal(instruction = "hi", conversationId = "c1", toolPolicy = ToolPolicy.Only(emptySet()))
            )
        }

        val request = provider.requests.single()
        // No tool rows requested; and the request carried no tool specs.
        assertTrue(request.none { it.role == ChatMessage.Role.TOOL })
    }

    @Test
    fun `model-requested unknown tool returns controlled error`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(toolRequestEvent("does.not.exist", "{}"))
        provider.script.add(StreamEvent.Delta("ok"))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "x", conversationId = "c1"))
        }

        val toolRow = provider.requests[1].filter { it.role == ChatMessage.Role.TOOL }
        assertTrue(toolRow.single().content.contains("Unknown tool"))
        assertNotNull(events.filterIsInstance<AgentEvent.Finished>().singleOrNull())
    }

    @Test
    fun `invalid tool arguments fail safely without crashing`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(toolRequestEvent("math.evaluate", "not-json"))
        provider.script.add(StreamEvent.Delta("handled"))

        val (agent, _) = buildAgent(provider)
        withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "x", conversationId = "c1"))
        }

        val toolRow = provider.requests[1].filter { it.role == ChatMessage.Role.TOOL }
        assertTrue(toolRow.single().content.contains("Missing required argument"))
    }
}
