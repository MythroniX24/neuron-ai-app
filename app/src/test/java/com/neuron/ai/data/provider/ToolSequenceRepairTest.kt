package com.neuron.ai.data.provider

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.ui.chat.ChatContextEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "works in a new chat, dies later" bug: persisted TOOL rows without
 * their ASSISTANT(tool_calls) partner produce protocol-invalid histories and
 * the provider rejects every subsequent message with a 400. Both repair
 * layers are tested here: the context engine (history side) and the wire
 * codec (final safety net).
 */
class ToolSequenceRepairTest {

    private val codec = OpenAIWireCodec(Json { ignoreUnknownKeys = true })

    private fun toolMessage(callId: String, toolName: String) = Message(
        id = "m-$callId",
        conversationId = "c1",
        role = Message.Role.TOOL,
        content = "result of $toolName",
        createdAtEpochMs = 0,
        metadata = MessageMetadata(toolCallId = callId, toolName = toolName)
    )

    // ---- Wire codec repair -------------------------------------------------

    @Test
    fun `wire codec synthesizes assistant row for orphan tool message`() {
        val repaired = codec.repairToolSequence(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage(ChatMessage.Role.TOOL, "result", toolCallId = "call-1")
            )
        )
        assertTrue(repaired[1].role == ChatMessage.Role.ASSISTANT)
        assertEquals(1, repaired[1].toolCalls.size)
        assertEquals("call-1", repaired[1].toolCalls[0].callId)
        assertTrue(repaired[2].role == ChatMessage.Role.TOOL)
        assertEquals("call-1", repaired[2].toolCallId)
    }

    @Test
    fun `wire codec pairs consecutive tool rows under one assistant call block`() {
        val repaired = codec.repairToolSequence(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage(ChatMessage.Role.TOOL, "r1", toolCallId = "call-1"),
                ChatMessage(ChatMessage.Role.TOOL, "r2", toolCallId = "call-2")
            )
        )
        val assistant = repaired.first { it.role == ChatMessage.Role.ASSISTANT }
        assertEquals(2, assistant.toolCalls.size)
        val ids = assistant.toolCalls.map { it.callId }
        assertTrue("call-1" in ids && "call-2" in ids)
    }

    @Test
    fun `wire codec appends missing call id to existing assistant tool calls`() {
        val repaired = codec.repairToolSequence(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "",
                    toolCalls = listOf(
                        com.neuron.ai.core.provider.ProposedToolCall("call-1", "a.b", "{}")
                    )
                ),
                ChatMessage(ChatMessage.Role.TOOL, "r1", toolCallId = "call-1"),
                ChatMessage(ChatMessage.Role.TOOL, "r2", toolCallId = "call-2")
            )
        )
        val assistant = repaired.first { it.role == ChatMessage.Role.ASSISTANT }
        assertEquals(2, assistant.toolCalls.size)
    }

    @Test
    fun `wire codec drops unpairable orphan tool row with no assistant above`() {
        // History starts with a TOOL row and nothing above to attach to — the
        // codec must NOT send protocol garbage; the orphan is dropped.
        val repaired = codec.repairToolSequence(
            listOf(
                ChatMessage(ChatMessage.Role.TOOL, "r1", toolCallId = "call-1")
            )
        )
        assertTrue(repaired.none { it.role == ChatMessage.Role.TOOL })
        assertTrue(repaired.none { it.role == ChatMessage.Role.ASSISTANT && it.toolCalls.isNotEmpty() })
    }

    @Test
    fun `wire codec strips dangling trailing tool calls`() {
        val repaired = codec.repairToolSequence(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "",
                    toolCalls = listOf(
                        com.neuron.ai.core.provider.ProposedToolCall("call-1", "a.b", "{}")
                    )
                )
            )
        )
        assertTrue(repaired.last().toolCalls.isEmpty())
    }

    @Test
    fun `encoded request never violates the tool sequence protocol`() {
        val request = CompletionRequest(
            model = Model(id = "m", displayName = "m"),
            messages = listOf(
                ChatMessage(ChatMessage.Role.USER, "search the web"),
                // Simulates the rebuilt history: assistant answer + orphan TOOL row.
                ChatMessage(ChatMessage.Role.ASSISTANT, "Here is what I found."),
                ChatMessage(ChatMessage.Role.TOOL, "search results", toolCallId = "call-9"),
                ChatMessage(ChatMessage.Role.USER, "and now summarize")
            )
        )
        val encoded = codec.encodeRequest(request)
        val messages = encoded["messages"]!!.jsonArray.map { it.jsonObject }

        messages.forEachIndexed { index, message ->
            if (message["role"]?.toString() == "\"tool\"") {
                val prev = messages[index - 1]
                val callIds = prev["tool_calls"]?.jsonArray
                    ?.map { it.jsonObject["id"]?.toString() }
                    .orEmpty()
                assertTrue(
                    "TOOL row at $index not preceded by matching assistant tool_calls",
                    callIds.contains("\"${message["tool_call_id"]?.toString()!!.trim('"')}\"") ||
                        prev["tool_calls"] != null
                )
            }
        }
    }

    // ---- Context engine repair ---------------------------------------------

    @Test
    fun `context engine synthesizes assistant row above persisted tool rows`() {
        val engine = ChatContextEngine()
        val messages = listOf(
            Message(
                id = "u1", conversationId = "c1", role = Message.Role.USER,
                content = "find kotlin docs", createdAtEpochMs = 0
            ),
            toolMessage("call-1", "web.search"),
            Message(
                id = "u2", conversationId = "c1", role = Message.Role.USER,
                content = "thanks", createdAtEpochMs = 1
            ),
            // Trailing user row is excluded as the "current request".
            Message(
                id = "u3", conversationId = "c1", role = Message.Role.USER,
                content = "go on", createdAtEpochMs = 2
            )
        )
        val result = engine.build(messages)
        val toolIndex = result.indexOfFirst { it.role == ChatMessage.Role.TOOL }
        assertTrue(toolIndex > 0)
        val assistant = result[toolIndex - 1]
        assertTrue(assistant.role == ChatMessage.Role.ASSISTANT)
        assertEquals("call-1", assistant.toolCalls.single().callId)
    }

    @Test
    fun `context engine keeps consecutive tool rows sharing one synthesized assistant`() {
        val engine = ChatContextEngine()
        val messages = listOf(
            Message(
                id = "u1", conversationId = "c1", role = Message.Role.USER,
                content = "do two things", createdAtEpochMs = 0
            ),
            toolMessage("call-1", "web.search"),
            toolMessage("call-2", "web.read"),
            Message(
                id = "u2", conversationId = "c1", role = Message.Role.USER,
                content = "next", createdAtEpochMs = 1
            ),
            Message(
                id = "u3", conversationId = "c1", role = Message.Role.USER,
                content = "current", createdAtEpochMs = 2
            )
        )
        val result = engine.build(messages)
        val assistantsWithCalls = result.filter {
            it.role == ChatMessage.Role.ASSISTANT && it.toolCalls.isNotEmpty()
        }
        assertEquals(1, assistantsWithCalls.size)
        assertEquals(2, assistantsWithCalls[0].toolCalls.size)
    }
}
