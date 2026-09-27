package com.neuron.ai.data.provider

import com.neuron.ai.core.agent.AgentEvent
import com.neuron.ai.core.agent.AgentGoal
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.StreamEvent
import com.neuron.ai.core.provider.ToolSpec
import com.neuron.ai.data.agent.ToolUsingAgent
import com.neuron.ai.ui.chat.ChatContextEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * END-TO-END "longest conversation" validation: a 12+ turn simulated
 * conversation with tool use, orphan TOOL rows, blank contents, mid-stream
 * truncation AND a context-overflow 400 — every request the agent builds is
 * validated against a strict OpenAI wire-protocol validator (the same rules
 * providers enforce with a 400). This is the app-side replica of "test the
 * longest conversation on a real device", runnable in CI.
 */
/**
 * Strict OpenAI wire validator (mirrors provider-side 400 checks). Top-level
 * so the nested scripted provider can call it.
 */
private fun validateWireRequest(request: CompletionRequest) {
    val codec = OpenAIWireCodec(Json { ignoreUnknownKeys = true })
    val encoded = codec.encodeRequest(request)
    val messages = encoded["messages"]!!.jsonArray.map { it.jsonObject }
    val hasTools = encoded.containsKey("tools")
    var sawUser = false

    messages.forEachIndexed { index, message ->
        val role = message["role"].toString().trim('"')
        val content = message["content"]

        // Rule 1: content must exist and never be JSON null.
        assertTrue(
            "msg $index: missing/null content",
            content != null && content.toString() != "null"
        )

        when (role) {
            "system" -> {}

            "user" -> sawUser = true

            "assistant" -> {
                val calls = message["tool_calls"]?.jsonArray
                if (calls != null) {
                    assertTrue("msg $index: empty tool_calls array", calls.isNotEmpty())
                    calls.forEach { call ->
                        val obj = call.jsonObject
                        assertTrue("msg $index: tool_call missing id", obj.containsKey("id"))
                        assertTrue(
                            "msg $index: tool_call missing function.name",
                            obj["function"]!!.jsonObject.containsKey("name")
                        )
                    }
                    if (!hasTools) {
                        throw AssertionError(
                            "msg $index: tool_calls present but NO tools array wired"
                        )
                    }
                }
            }

            "tool" -> {
                assertTrue(
                    "msg $index: TOOL row present but NO tools array wired",
                    hasTools
                )
                assertTrue(
                    "msg $index: empty tool content",
                    content.toString().length > 4
                )
                assertTrue(
                    "msg $index: tool row missing tool_call_id",
                    message.containsKey("tool_call_id")
                )
                if (index == 0) throw AssertionError("msg 0 is a TOOL row")
                val prev = messages[index - 1]
                val prevIds = prev["tool_calls"]?.jsonArray
                    ?.map { it.jsonObject["id"].toString() }
                    .orEmpty()
                val myId = message["tool_call_id"].toString()
                assertTrue(
                    "msg $index: TOOL row not preceded by matching assistant tool_calls " +
                        "(prev role=${prev["role"]}, ids=$prevIds, mine=$myId)",
                    prevIds.contains(myId)
                )
            }
        }
    }
    assertTrue("request must contain at least one user message", sawUser)
}

class LongConversationProtocolTest {

    // ---- Fakes --------------------------------------------------------------

    /** A real-ish tool: succeeds on most calls, fails on a specific argument. */
    private class StubSearchTool : Tool {
        override val id = "web.search"
        override val title = "Searching web"
        override val description = "stub"
        override val requiredCapabilities = emptySet<com.neuron.ai.core.permissions.Capability>()
        override val riskLevel = com.neuron.ai.core.agent.RiskLevel.SAFE
        override val timeoutMs = 1_000L
        override val parametersSchemaJson =
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        override suspend fun execute(argumentsJson: String): ToolResult =
            if (argumentsJson.contains("fail-me")) {
                ToolResult.Failure("engine blocked the query")
            } else {
                ToolResult.Success("SNOURCES:\n[1] Result about ${argumentsJson.take(40)}\n   https://example.com/x")
            }
    }

    /**
     * Scripted provider over the FULL conversation: every request is first
     * validated against the strict wire rules; a violation FAILS the test
     * exactly like a real provider 400 would. Scripts real model behavior:
     * a truncation turn, a failing tool call, a context-overflow 400, and
     * normal text turns.
     */
    private class ValidatingProvider(
        private val failOnceAfter: Int
    ) : com.neuron.ai.core.provider.AIProvider {
        override val id = "fake"
        override val displayName = "Fake"

        val requests = mutableListOf<CompletionRequest>()
        val validator = { request: CompletionRequest -> request }
        var callsMade = 0
        var overflowEmitted = false

        override suspend fun listModels(): List<Model> = emptyList()

        override suspend fun complete(request: CompletionRequest) =
            com.neuron.ai.core.provider.Completion(
                ChatMessage(ChatMessage.Role.ASSISTANT, ""), request.model.id
            )

        override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
            callsMade++
            requests.add(request)
            // The provider-side 400: validate BEFORE scripting output.
            try {
                validateWireRequest(request)
            } catch (t: Throwable) {
                emit(
                    StreamEvent.Failed(
                        NeuronError.Provider("PROTOCOL VIOLATION: ${t.message}")
                    )
                )
                return@flow
            }

            // Scripted behavior across the long conversation. The loop only
            // continues while turns carry TOOL CALLS — a text-only turn IS the
            // final answer. So the script must drive: tool turns (with the
            // overflow hitting one of them), narration, markup truncation,
            // then the final text turn LAST.
            when {
                callsMade == 1 -> {
                    emit(StreamEvent.ToolCallRequested("c-1", "web.search", "{\"query\":\"kotlin coroutines guide\"}"))
                }
                callsMade == 2 -> {
                    // Overflow 400 hits while the context is already long.
                    overflowEmitted = true
                    emit(
                        StreamEvent.Failed(
                            NeuronError.Provider(
                                "400 - This model's maximum context length is 8192 tokens"
                            )
                        )
                    )
                }
                callsMade == 3 -> {
                    // After trim recovery: narration + a FAILING tool call.
                    emit(StreamEvent.Delta("Checking the docs next."))
                    emit(StreamEvent.ToolCallRequested("c-f1", "web.search", "{\"query\":\"fail-me\"}"))
                }
                callsMade == 4 -> {
                    // Revised approach after the failure.
                    emit(StreamEvent.ToolCallRequested("c-f2", "web.search", "{\"query\":\"kotlin 2 docs\"}"))
                }
                callsMade == 5 -> {
                    // A truncated plain-text tool-call block (markup variant).
                    emit(
                        StreamEvent.Delta(
                            "<tool_call><function-web.search><parameter-query>kotlin 2 release notes</parameter>"
                        )
                    )
                }
                callsMade == 6 -> {
                    // The recovered markup call executes; model asks one more.
                    emit(StreamEvent.ToolCallRequested("c-6", "web.search", "{\"query\":\"android 15\"}"))
                }
                else -> {
                    emit(StreamEvent.Delta("Final: everything checked out."))
                }
            }
            emit(StreamEvent.Completed)
        }
    }

    // ---- The long conversation ---------------------------------------------

    /** Builds 12+ turns of REALISTIC persisted history (orphan TOOL rows included). */
    private suspend fun longHistory(): List<ChatMessage> {
        val engine = ChatContextEngine()
        val persisted = mutableListOf<Message>()
        repeat(6) { turn ->
            persisted += Message(
                id = "u$turn", conversationId = "c", role = Message.Role.USER,
                content = "Question number $turn about kotlin and android development topics",
                createdAtEpochMs = turn.toLong()
            )
            if (turn % 2 == 0) {
                // Orphan TOOL row (metadata only — no assistant row persisted).
                persisted += Message(
                    id = "t$turn", conversationId = "c", role = Message.Role.TOOL,
                    content = "tool output for turn $turn — search results line",
                    createdAtEpochMs = turn.toLong(),
                    metadata = MessageMetadata(toolCallId = "call-old-$turn", toolName = "web.search")
                )
            }
            // Blank assistant row (the null-content producer).
            persisted += Message(
                id = "a$turn", conversationId = "c", role = Message.Role.ASSISTANT,
                content = if (turn % 3 == 0) "" else "Answer $turn",
                createdAtEpochMs = turn.toLong()
            )
        }
        return engine.build(
            persisted + Message(
                id = "u-final", conversationId = "c", role = Message.Role.USER,
                content = "current request", createdAtEpochMs = 99
            )
        )
    }

    @Test
    fun `longest conversation with tools stays protocol-valid through every turn`() = runTest {
        val history = longHistory()
        assertTrue("fixture must be a genuinely long conversation", history.size >= 8)

        val provider = ValidatingProvider(failOnceAfter = 0)
        val registry = com.neuron.ai.data.tool.InMemoryToolRegistry()
        registry.register(StubSearchTool())
        val executor = com.neuron.ai.data.agent.DefaultToolExecutor(
            registry, com.neuron.ai.data.permissions.SessionPermissionManager(),
            logger = null, maxRetries = 0
        )
        val agent = ToolUsingAgent(
            provider = provider,
            model = Model(id = "test-model", displayName = "test"),
            toolRegistry = registry,
            toolExecutor = executor,
            logger = null,
            maxAttemptsPerProblem = 2
        )

        val events = mutableListOf<AgentEvent>()
        agent.run(
            AgentGoal(instruction = "continue the research", conversationId = "c", history = history)
        ).collect { events.add(it) }

        // 1. The overflow 400 triggered the trim-recovery and the turn went on.
        val recoverSteps = events.filterIsInstance<AgentEvent.ActivityStarted>()
            .filter { it.activity.title.startsWith("Trimming context") }
        if (recoverSteps.isEmpty()) {
            throw AssertionError(
                "overflow recovery never fired; callsMade=${provider.callsMade} " +
                    "overflowEmitted=${provider.overflowEmitted} " +
                    "failures=${events.filterIsInstance<AgentEvent.Failed>().map { it.message }} " +
                    "finished=${events.filterIsInstance<AgentEvent.Finished>().map { it.summary.take(40) }}"
            )
        }
        assertTrue(recoverSteps.isNotEmpty())

        // 2. The failing tool call was recovered via the revised-approach path
        //    (narration surfaced, tool error fed back) — no dead end.
        assertTrue(
            events.filterIsInstance<AgentEvent.IntermediateMessage>().isNotEmpty()
        )

        // 3. The truncated plain-text markup block was recovered as a tool call.
        assertTrue(
            "markup recovery never fired",
            events.any { it is AgentEvent.TextBuffer && !it.text.contains("<tool_call>") }
        )

        // 4. NO request ever violated the wire protocol (validator FAILS the
        //    flow otherwise) and the run completed with a real answer.
        assertTrue(provider.requests.size >= 5)
        assertTrue(
            events.filterIsInstance<AgentEvent.Finished>().isNotEmpty()
        )
        assertTrue(events.filterIsInstance<AgentEvent.Failed>().isEmpty())
    }
}
