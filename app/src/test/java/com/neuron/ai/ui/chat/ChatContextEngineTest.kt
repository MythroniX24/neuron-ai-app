package com.neuron.ai.ui.chat

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.provider.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 3 context-engine tests: compression, budget, memory, wrapper. */
class ChatContextEngineTest {

    private fun msg(role: Message.Role, content: String, toolName: String? = null) = Message(
        id = "m-" + content.hashCode().toString(),
        conversationId = "c",
        role = role,
        content = content,
        createdAtEpochMs = 0,
        metadata = toolName?.let {
            com.neuron.ai.core.conversation.MessageMetadata(toolCallId = "call-1", toolName = it)
        }
    )

    @Test
    fun `legacy wrapper keeps old behavior`() {
        val out = buildChatContext(
            listOf(
                msg(Message.Role.USER, "hi"),
                msg(Message.Role.ASSISTANT, "hello"),
                msg(Message.Role.USER, "current question")
            )
        )
        assertEquals(2, out.size)
        assertEquals(ChatMessage.Role.USER, out[0].role)
        assertEquals("hi", out[0].content)
    }

    @Test
    fun `engine compresses tool rows instead of dropping them`() = runTest {
        val longTool = "exit=1\n" + (1..200).joinToString("\n") { "line $it with error details" }
        val engine = ChatContextEngine()
        val out = engine.build(
            listOf(
                msg(Message.Role.USER, "run it"),
                msg(Message.Role.ASSISTANT, "ok", toolName = "Running command"),
                msg(Message.Role.TOOL, longTool, toolName = "Running command"),
                msg(Message.Role.USER, "what happened?")
            )
        )
        // The trailing USER row is the current send; TOOL row stays (compressed).
        val toolRows = out.filter { it.role == ChatMessage.Role.TOOL }
        assertEquals(1, toolRows.size)
        assertTrue(toolRows[0].content.length < longTool.length)
        assertTrue(toolRows[0].content.contains("exit=1"))
    }

    @Test
    fun `engine injects memory block as system message`() = runTest {
        val engine = ChatContextEngine(memoryBlockProvider = { "PREFERENCES:\n- language: Kotlin" })
        val out = engine.build(listOf(msg(Message.Role.USER, "hello")))
        val system = out.filter { it.role == ChatMessage.Role.SYSTEM }
        assertEquals(1, system.size)
        assertTrue(system[0].content.contains("language: Kotlin"))
    }

    @Test
    fun `budget trims oldest first and keeps tail intact`() = runTest {
        val engine = ChatContextEngine()
        val big = "x".repeat(20_000)
        val messages = buildList {
            add(msg(Message.Role.USER, big))
            add(msg(Message.Role.ASSISTANT, big))
            add(msg(Message.Role.USER, "recent user message"))
        }
        // The current send rides in via directUserText (as in the
        // attachments-only path); the trailing USER row is context.
        val out = engine.build(messages, directUserText = "final question that must survive")
        assertTrue(out.last().content.contains("final question that must survive"))
        val total = out.sumOf { it.content.length }
        // Budget: the OLDEST message trims to ~400 chars first; the newer
        // assistant message (tail) stays whole, as does the current send.
        assertTrue("total=$total", total <= 24_000)
        assertTrue("tail intact", out.any { it.content.length > 10_000 })
    }

    @Test
    fun `direct user text is appended when provided`() = runTest {
        val engine = ChatContextEngine()
        val out = engine.build(emptyList(), directUserText = "standalone question")
        assertEquals(1, out.size)
        assertEquals("standalone question", out[0].content)
    }
}
