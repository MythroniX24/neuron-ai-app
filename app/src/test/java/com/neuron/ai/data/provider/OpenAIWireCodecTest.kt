package com.neuron.ai.data.provider

import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.StreamEvent
import com.neuron.ai.core.provider.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for the OpenAI-compatible wire codec and SSE parser.
 * Pure Kotlin — no Android dependencies.
 */
class OpenAIWireCodecTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val codec = OpenAIWireCodec(json)

    // ---- Request encoding ------------------------------------------------------------

    @Test
    fun `encodeRequest includes model, messages and stream flag`() {
        val request = CompletionRequest(
            model = Model(id = "gpt-test", displayName = "gpt-test"),
            messages = listOf(
                ChatMessage(role = ChatMessage.Role.SYSTEM, content = "You are helpful."),
                ChatMessage(role = ChatMessage.Role.USER, content = "Hello")
            )
        )

        val encoded = codec.encodeRequest(request).jsonObject

        assertEquals("gpt-test", encoded["model"]!!.jsonPrimitive.content)
        assertEquals(true, encoded["stream"]!!.jsonPrimitive.content.toBooleanStrictOrNull()
            ?: (encoded["stream"]!!.jsonPrimitive.content == "true"))
        val messages = encoded["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodeRequest encodes tools with JSON schemas`() {
        val request = CompletionRequest(
            model = Model(id = "m", displayName = "m"),
            messages = listOf(ChatMessage(role = ChatMessage.Role.USER, content = "hi")),
            tools = listOf(
                ToolSpec(
                    id = "math.evaluate",
                    description = "Evaluates arithmetic",
                    parametersSchemaJson = """{"type":"object","properties":{"expression":{"type":"string"}}}"""
                )
            )
        )

        val encoded = codec.encodeRequest(request).jsonObject
        val tools = encoded["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val function = tools[0].jsonObject["function"]!!.jsonObject
        assertEquals("math.evaluate", function["name"]!!.jsonPrimitive.content)
        assertNotNull(function["parameters"]!!.jsonObject["properties"])
    }

    @Test
    fun `encodeRequest emits max_tokens only when set`() {
        val base = CompletionRequest(
            model = Model(id = "m", displayName = "m"),
            messages = emptyList()
        )
        val without = codec.encodeRequest(base).jsonObject
        assertTrue(without["max_tokens"] == null)

        val with = codec.encodeRequest(base.copy(maxOutputTokens = 256)).jsonObject
        assertEquals(256, with["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `assistant tool calls round-trip through the wire format`() {
        val message = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            content = "",
            toolCalls = listOf(
                com.neuron.ai.core.provider.ProposedToolCall(
                    callId = "call-1",
                    toolId = "time.now",
                    argumentsJson = "{}"
                )
            )
        )

        val encoded = codec.encodeMessage(message).jsonObject
        val calls = encoded["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        assertEquals("call-1", calls[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(
            "time.now",
            calls[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        )
    }

    // ---- SSE parsing ------------------------------------------------------------------

    @Test
    fun `parser emits deltas for content chunks`() {
        val parser = SseChunkParser(json)
        val events = parser.parse(
            """{"choices":[{"delta":{"content":"Hel"}}]}"""
        )

        assertEquals(1, events.size)
        val delta = events[0] as StreamEvent.Delta
        assertEquals("Hel", delta.text)
    }

    @Test
    fun `parser assembles tool call fragments across chunks`() {
        val parser = SseChunkParser(json)

        parser.parse("""{"choices":[{"delta":{"tool_calls":[{"index":"0","id":"call-9","function":{"name":"math.evaluate","arguments":"{\"expr"}}]}}]}""")
        parser.parse("""{"choices":[{"delta":{"tool_calls":[{"index":"0","function":{"arguments":"ession\":\"1+2\"}\"}}]}}]}""")
        val events = parser.parse("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")

        val call = events.filterIsInstance<StreamEvent.ToolCallRequested>().single()
        assertEquals("call-9", call.callId)
        assertEquals("math.evaluate", call.toolId)
        assertEquals("""{"expression":"1+2"}""", call.argumentsJson)
    }

    @Test
    fun `parser emits Completed on DONE sentinel`() {
        val parser = SseChunkParser(json)
        val events = parser.parse("[DONE]")
        assertEquals(listOf<StreamEvent>(StreamEvent.Completed), events)
    }

    @Test
    fun `parser surfaces provider error objects as Failed events`() {
        val parser = SseChunkParser(json)
        val events = parser.parse("""{"error":{"message":"quota exceeded"}}""")

        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.error.message.contains("quota exceeded"))
    }

    @Test
    fun `parser ignores malformed and empty payloads`() {
        val parser = SseChunkParser(json)
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("""{"choices":[]}""").isEmpty())
    }

    // ---- HTTP error mapping ------------------------------------------------------------

    @Test
    fun `mapHttpError produces user-friendly errors per status`() {
        val cases = mapOf(
            401 to "Invalid or missing API key.",
            403 to "Access denied by the provider.",
            404 to "Model or endpoint not found. Check the model id and base URL.",
            429 to "Rate limit reached. Wait a moment and try again.",
            503 to "Provider server error. Try again shortly."
        )
        cases.forEach { (code, expected) ->
            val error = OpenAIWireCodec.mapHttpError(null, code)
            assertEquals("HTTP $code", expected, error.message)
            assertTrue("HTTP $code should be a Provider error", error is NeuronError.Provider)
        }
    }

    @Test
    fun `mapHttpError prefers the remote error message for unmapped codes`() {
        val body = """{"error":{"message":"custom upstream failure"}}"""
        val error = OpenAIWireCodec.mapHttpError(body, 418)

        assertEquals("custom upstream failure", error.message)
    }
}
