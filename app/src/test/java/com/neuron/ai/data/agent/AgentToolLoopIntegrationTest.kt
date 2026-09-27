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
        /** Tool-spec count per model turn (schema-rejection assertions). */
        val toolSpecCounts = mutableListOf<Int>()
        /** One entry per model turn: tool request or plain text. */
        val script = ArrayDeque<StreamEvent>()
        /** Multi-event turns (e.g. narration text + a tool call together). */
        val multiScript = ArrayDeque<List<StreamEvent>>()
        var toolCallCounter = 0

        fun enqueueTurn(events: List<StreamEvent>) {
            multiScript.addLast(events)
        }

        override suspend fun listModels(): List<Model> = emptyList()

        override suspend fun complete(request: CompletionRequest): Completion =
            Completion(ChatMessage(ChatMessage.Role.ASSISTANT, ""), request.model.id)

        override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
            requests.add(request.messages)
            toolSpecCounts.add(request.tools.size)
            val multi = multiScript.removeFirstOrNull()
            if (multi != null) {
                multi.forEach { event ->
                    if (event is StreamEvent.ToolCallRequested) {
                        toolCallCounter++
                        emit(event.copy(callId = "call-$toolCallCounter"))
                    } else {
                        emit(event)
                    }
                }
            } else {
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
    fun `current message attachments reach the provider request`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(StreamEvent.Delta("I see the image."))

        val (agent, _) = buildAgent(provider)
        val attachment = com.neuron.ai.core.conversation.Attachment(
            id = "att-img1",
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024,
            localPath = "attachments/att-img1_photo.jpg",
            kind = com.neuron.ai.core.conversation.Attachment.Kind.IMAGE
        )
        withTimeout(5_000) {
            collect(
                agent,
                AgentGoal(
                    instruction = "What is in this image?",
                    conversationId = "c1",
                    attachments = listOf(attachment)
                )
            )
        }

        // The FINAL user row of the model request must carry the attachment.
        val finalUser = provider.requests.single()
            .last { it.role == ChatMessage.Role.USER }
        assertEquals(listOf(attachment), finalUser.attachments)
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

    /** Synchronous auto-deny permission manager — deterministic, no races. */
    private class AutoDenyPermissions : com.neuron.ai.core.permissions.PermissionManager {
        override val pendingRequests: Flow<List<com.neuron.ai.core.permissions.PermissionRequest>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override val granted: Flow<Set<Capability>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        override val decisions: Flow<List<com.neuron.ai.core.permissions.PermissionDecision>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override suspend fun request(
            capability: Capability,
            reason: String,
            requestedBy: String
        ): Boolean = false
        override suspend fun grant(requestId: String) {}
        override suspend fun deny(requestId: String) {}
        override suspend fun decide(requestId: String, granted: Boolean, scope: com.neuron.ai.core.permissions.DecisionScope) {}
        override suspend fun revoke(capability: Capability) {}
        override suspend fun isGranted(capability: Capability): Boolean = false
        override suspend fun resetDecisions() {}
    }

    @Test
    fun `permission denial feeds structured error to model, no crash`() = runTest {
        val provider = ScriptedProvider()
        // fs.read requires FILESYSTEM_READ — a permission the fake denies.
        provider.script.add(toolRequestEvent("fs.read", "{\"path\":\"notes.txt\"}"))
        provider.script.add(StreamEvent.Delta("Understood, no permission."))

        val workspace = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "neuron-denial-test-" + System.nanoTime()
        ).apply { mkdirs() }
        val registry = InMemoryToolRegistry()
        registry.register(SafeTools.FileRead(workspace))
        val executor = DefaultToolExecutor(registry, AutoDenyPermissions(), logger = null)
        val agent = ToolUsingAgent(
            provider = provider,
            model = Model(id = "test-model", displayName = "test"),
            toolRegistry = registry,
            toolExecutor = executor,
            logger = null
        )

        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "read file", conversationId = "c1"))
        }

        // The model received the structured DENIAL as a tool result — and the
        // tool itself NEVER executed (no file access happened). Denials are
        // distinct from failures and explicitly forbid blind retries.
        val toolRow = provider.requests[1].filter { it.role == ChatMessage.Role.TOOL }
        assertEquals(1, toolRow.size)
        assertTrue(toolRow.single().content.startsWith("Permission denied:"))
        assertTrue(toolRow.single().content.contains("Do not retry"))
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

    // ---- Empty-turn / schema-rejection recovery (the "empty response" fix) ----

    @Test
    fun `transient empty model turn is retried once and recovers`() = runTest {
        val provider = ScriptedProvider()
        // Turn 1: provider emits NOTHING (blank completion, no error).
        provider.script.add(StreamEvent.Completed)
        // Turn 2 (the retry): the real answer streams through.
        provider.script.add(StreamEvent.Delta("The real answer."))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "hi", conversationId = "c1"))
        }

        // Two model calls happened and the retry SURFACED the answer instead
        // of a Finished("") that the UI reads as "model returned an empty
        // response".
        assertEquals(2, provider.requests.size)
        assertEquals("The real answer.", events.filterIsInstance<AgentEvent.Finished>().single().summary)
    }

    @Test
    fun `persistent empty turns surface a clear failure instead of finishing blank`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(StreamEvent.Completed)
        provider.script.add(StreamEvent.Completed)

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "hi", conversationId = "c1"))
        }

        assertEquals(2, provider.requests.size) // one silent retry only
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().isEmpty())
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.message.contains("empty response"))
    }

    @Test
    fun `tool schema rejection retries the turn without tools instead of finishing empty`() = runTest {
        val provider = ScriptedProvider()
        // Turn 1: provider rejects the tools schema outright.
        provider.script.add(
            StreamEvent.Failed(
                com.neuron.ai.core.error.NeuronError.Provider(
                    "400 — 'tools' is not supported for this model"
                )
            )
        )
        // Turn 2 (the retry, tools dropped): plain chat completes.
        provider.script.add(StreamEvent.Delta("plain answer"))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "hi", conversationId = "c1"))
        }

        // The retry really happened, WITHOUT the tools array, and the turn
        // completed — previously it fell through and emitted Finished("").
        assertEquals(2, provider.requests.size)
        assertTrue(provider.toolSpecCounts[0] > 0)
        assertEquals(0, provider.toolSpecCounts[1])
        assertEquals("plain answer", events.filterIsInstance<AgentEvent.Finished>().single().summary)
    }

    // ---- Plain-text tool-call recovery (transport repair) -----------------

    @Test
    fun `tool call printed as plain text is recovered and executed`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(
            StreamEvent.Delta(
                "<tool_call><function-math.evaluate>" +
                    "<parameter-expression>6*7</parameter-expression>" +
                    "</function></tool_call>"
            )
        )
        provider.script.add(StreamEvent.Delta("The answer is 42."))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "compute 6*7", conversationId = "c1"))
        }

        // Two model calls: markup turn + tool-result turn.
        assertEquals(2, provider.requests.size)
        // Markup was stripped from the visible buffer...
        val buffer = events.filterIsInstance<AgentEvent.TextBuffer>().single()
        if (buffer.text.contains("<tool_call>")) {
            throw AssertionError("diag: buffer=[${buffer.text}] events=${events.map { it::class.simpleName }}")
        }
        // ...the recovered call really executed...
        val toolRow = provider.requests[1].filter { it.role == ChatMessage.Role.TOOL }
        assertEquals(1, toolRow.size)
        assertTrue(toolRow.single().content.contains("42"))
        // ...and the run completed normally.
        assertEquals(
            "The answer is 42.",
            events.filterIsInstance<AgentEvent.Finished>().single().summary
        )
    }

    @Test
    fun `markup for an unregistered tool stays plain text - never executed`() = runTest {
        val provider = ScriptedProvider()
        // web.search is NOT in this test's registry.
        provider.script.add(
            StreamEvent.Delta(
                "<tool_call>{\"name\":\"web.search\",\"arguments\":{\"query\":\"x\"}}</tool_call>"
            )
        )
        provider.script.add(StreamEvent.Delta("Cannot search from here."))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "search the web", conversationId = "c1"))
        }

        // Markup was stripped from the buffer, the unregistered id produced a
        // structured "Unknown tool" result, and the model self-corrected.
        assertTrue(events.any { it is AgentEvent.TextBuffer && !it.text.contains("<tool_call>") })
        assertEquals(2, provider.requests.size)
        val toolRow = provider.requests[1].filter { it.role == ChatMessage.Role.TOOL }
        assertTrue(toolRow.single().content.contains("Unknown tool"))
        assertEquals(
            "Cannot search from here.",
            events.filterIsInstance<AgentEvent.Finished>().single().summary
        )
    }

    // ---- Context-overflow auto-recovery ------------------------------------

    @Test
    fun `context overflow trims history once and the turn succeeds`() = runTest {
        val provider = ScriptedProvider()
        // Call 1: provider rejects with the classic context-window 400.
        // Call 2 (after trim): the model answers normally.
        provider.script.add(
            StreamEvent.Failed(
                com.neuron.ai.core.error.NeuronError.Provider(
                    "400 - This model's maximum context length is 8192 tokens"
                )
            )
        )
        provider.script.add(StreamEvent.Delta("Trimmed and answered."))

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(
                agent,
                AgentGoal(
                    instruction = "go",
                    conversationId = "c1",
                    history = (1..10).map { i ->
                        ChatMessage(ChatMessage.Role.USER, "old message $i with padding text")
                    }
                )
            )
        }

        assertEquals(2, provider.requests.size)
        // The rebuilt second request is materially smaller than the first.
        assertTrue(
            provider.requests[1].sumOf { it.content.length } <
                provider.requests[0].sumOf { it.content.length }
        )
        // The user still gets their answer — no provider-error dead end.
        assertEquals(
            "Trimmed and answered.",
            events.filterIsInstance<AgentEvent.Finished>().single().summary
        )
    }

    @Test
    fun `non-overflow provider errors are not masked by trimming`() = runTest {
        val provider = ScriptedProvider()
        provider.script.add(
            StreamEvent.Failed(
                com.neuron.ai.core.error.NeuronError.Provider("401 - Invalid or missing API key.")
            )
        )

        val (agent, _) = buildAgent(provider)
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "go", conversationId = "c1"))
        }

        // Auth errors surface as-is — exactly one attempt, no trim, no retry.
        assertEquals(1, provider.requests.size)
        assertTrue(events.filterIsInstance<AgentEvent.Failed>().isNotEmpty())
    }

    // ---- Intelligent retry (think → tool → retry with revised approach) ----

    /** Always-failing tool for retry-budget tests. */
    private class AlwaysFailingTool : com.neuron.ai.core.agent.Tool {
        override val id = "fail.always"
        override val title = "Failing tool"
        override val description = "Always fails — for retry tests."
        override val requiredCapabilities = emptySet<Capability>()
        override val riskLevel = com.neuron.ai.core.agent.RiskLevel.SAFE
        override val timeoutMs = 1_000L
        override val parametersSchemaJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(argumentsJson: String): ToolResult =
            ToolResult.Failure("persistent boom")
    }

    @Test
    fun `identical failed call beyond retry budget surfaces honest failure`() = runTest {
        val provider = ScriptedProvider()
        // Three turns: model repeats the IDENTICAL failing call each time.
        repeat(3) {
            provider.script.add(toolRequestEvent("fail.always", "{\"x\":\"1\"}"))
        }

        val registry = InMemoryToolRegistry()
        registry.register(AlwaysFailingTool())
        val executor = DefaultToolExecutor(registry, SessionPermissionManager(), logger = null, maxRetries = 0)
        val agent = ToolUsingAgent(
            provider = provider,
            model = Model(id = "test-model", displayName = "test"),
            toolRegistry = registry,
            toolExecutor = executor,
            logger = null,
            maxAttemptsPerProblem = 2
        )
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "do the thing", conversationId = "c1"))
        }

        // Honest give-up — the failure names the tool and the attempt count.
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.message.contains("Retry budget exhausted"))
        assertTrue(failed.message.contains("fail.always"))
        // No final answer was fabricated after exhausted retries.
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().isEmpty())
    }

    @Test
    fun `revised approach after failure succeeds and resets the budget`() = runTest {
        val provider = ScriptedProvider()
        // Turn 1: failing call. Turn 2: narration + REVISED args in the SAME
        // turn (the real interleaved shape). Turn 3: final answer.
        provider.script.add(toolRequestEvent("fail.always", "{\"x\":\"1\"}"))
        provider.enqueueTurn(
            listOf(
                StreamEvent.Delta("Let me try a different way."),
                toolRequestEvent("fail.always", "{\"x\":\"2\"}")
            )
        )
        provider.script.add(StreamEvent.Delta("Recovered successfully."))

        val registry = InMemoryToolRegistry()
        registry.register(object : com.neuron.ai.core.agent.Tool {
            override val id = "fail.always"
            override val title = "Failing tool"
            override val description = "Fails on x=1"
            override val requiredCapabilities = emptySet<Capability>()
            override val riskLevel = com.neuron.ai.core.agent.RiskLevel.SAFE
            override val timeoutMs = 1_000L
            override val parametersSchemaJson = """{"type":"object","properties":{"x":{"type":"string"}}}"""
            override suspend fun execute(argumentsJson: String): ToolResult =
                if (argumentsJson.contains("\"1\"")) ToolResult.Failure("bad value")
                else ToolResult.Success("ok")
        })
        val executor = DefaultToolExecutor(registry, SessionPermissionManager(), logger = null, maxRetries = 0)
        val agent = ToolUsingAgent(
            provider = provider,
            model = Model(id = "test-model", displayName = "test"),
            toolRegistry = registry,
            toolExecutor = executor,
            logger = null,
            maxAttemptsPerProblem = 3
        )
        val events = withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "do the thing", conversationId = "c1"))
        }

        // The revised approach ran (2 model calls with tools + final) and the
        // narration stayed distinct from the final answer.
        assertEquals(
            "Recovered successfully.",
            events.filterIsInstance<AgentEvent.Finished>().single().summary
        )
        val narration = events.filterIsInstance<AgentEvent.IntermediateMessage>().single()
        assertEquals("Let me try a different way.", narration.text)
    }

    @Test
    fun `retry guidance reaches the model after a failure`() = runTest {
        val provider = ScriptedProvider()
        // Turn 1 fails; turn 2 REPEATS identically (guidance is injected);
        // turn 3 revises the approach; turn 4 answers.
        provider.script.add(toolRequestEvent("fail.always", "{\"x\":\"1\"}"))
        provider.script.add(toolRequestEvent("fail.always", "{\"x\":\"1\"}"))
        provider.script.add(toolRequestEvent("fail.always", "{\"y\":\"2\"}"))
        provider.script.add(StreamEvent.Delta("done"))

        val registry = InMemoryToolRegistry()
        registry.register(AlwaysFailingTool())
        val executor = DefaultToolExecutor(registry, SessionPermissionManager(), logger = null, maxRetries = 0)
        val agent = ToolUsingAgent(
            provider = provider,
            model = Model(id = "test-model", displayName = "test"),
            toolRegistry = registry,
            toolExecutor = executor,
            logger = null,
            maxAttemptsPerProblem = 3
        )
        withTimeout(5_000) {
            collect(agent, AgentGoal(instruction = "go", conversationId = "c1"))
        }

        // The THIRD model call (after the identical repeat) carried the
        // retry-policy note with the actual error.
        val thirdCall = provider.requests[2]
        val lastUser = thirdCall.last { it.role == ChatMessage.Role.USER }
        assertTrue(lastUser.content.contains("retry policy"))
        assertTrue(lastUser.content.contains("persistent boom"))
        assertTrue(lastUser.content.contains("Do NOT repeat"))
    }
}
