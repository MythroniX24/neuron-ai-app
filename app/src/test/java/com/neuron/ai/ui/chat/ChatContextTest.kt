package com.neuron.ai.ui.chat

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.provider.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Context assembly and title derivation for the chat engine. */
class ChatContextTest {

    private fun message(
        id: String,
        role: Message.Role,
        content: String,
        isError: Boolean = false
    ) = Message(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        createdAtEpochMs = 0,
        metadata = if (isError) {
            com.neuron.ai.core.conversation.MessageMetadata(isError = true)
        } else {
            null
        }
    )

    // ---- buildChatContext ----------------------------------------------------------------

    @Test
    fun `context includes prior turns in order`() {
        val messages = listOf(
            message("m1", Message.Role.USER, "first"),
            message("m2", Message.Role.ASSISTANT, "answer one"),
            message("m3", Message.Role.USER, "second")
        )

        val context = buildChatContext(messages)

        assertEquals(2, context.size)
        assertEquals(ChatMessage.Role.USER, context[0].role)
        assertEquals("first", context[0].content)
        assertEquals(ChatMessage.Role.ASSISTANT, context[1].role)
        assertEquals("answer one", context[1].content)
    }

    @Test
    fun `trailing non-user rows are excluded - the live prompt is not doubled`() {
        val messages = listOf(
            message("m1", Message.Role.USER, "old question"),
            message("m2", Message.Role.ASSISTANT, "old answer"),
            // What send() just appended:
            message("m3", Message.Role.USER, "the new prompt"),
            // A partial answer from a stopped attempt:
            message("m4", Message.Role.ASSISTANT, "partial…")
        )

        val context = buildChatContext(messages)

        // Only turns BEFORE the newest user message remain.
        assertEquals(listOf("old question", "old answer"), context.map { it.content })
    }

    @Test
    fun `tool rows and error rows are never sent to the model`() {
        val messages = listOf(
            message("m1", Message.Role.USER, "question"),
            message("m2", Message.Role.TOOL, "raw tool output"),
            message("m3", Message.Role.ASSISTANT, "answer"),
            message("m4", Message.Role.ASSISTANT, "failed: timeout", isError = true),
            message("m5", Message.Role.USER, "follow up")
        )

        val context = buildChatContext(messages)

        assertEquals(listOf("question", "answer"), context.map { it.content })
    }

    @Test
    fun `history is capped to the most recent turns`() {
        val messages = (1..50).flatMap { i ->
            listOf(
                message("u$i", Message.Role.USER, "question $i"),
                message("a$i", Message.Role.ASSISTANT, "answer $i")
            )
        } + message("last", Message.Role.USER, "current")

        val context = buildChatContext(messages)

        assertEquals(30, context.size)
        assertEquals("question 36", context.first().content)
        assertTrue(!context.any { it.content == "question 1" })
    }

    @Test
    fun `first message of a conversation yields empty context`() {
        val messages = listOf(message("m1", Message.Role.USER, "hello there"))

        assertTrue(buildChatContext(messages).isEmpty())
    }

    // ---- deriveChatTitle -------------------------------------------------------------------

    @Test
    fun `title takes first meaningful words`() {
        assertEquals("Write a parser", deriveChatTitle("Write a parser for markdown files please"))
        assertEquals(
            "Explain quantum entanglement",
            deriveChatTitle("Explain quantum entanglement like I am five")
        )
    }

    @Test
    fun `title strips markdown urls and code`() {
        val title = deriveChatTitle(
            "**Bold** start [link](https://example.com/x) `code` and ```blocked``` words here"
        )
        assertTrue("Got: $title", !title.contains("http"))
        assertTrue("Got: $title", !title.contains("*"))
        assertTrue("Got: $title", title.split(" ").size <= 3)
    }

    @Test
    fun `title is capped in length`() {
        val long = deriveChatTitle("This is a very long opening message about many things at once")
        assertTrue("Got: $long", long.length <= 31)
    }

    @Test
    fun `empty or symbol-only messages fall back to New chat`() {
        assertEquals("New chat", deriveChatTitle(""))
        assertEquals("New chat", deriveChatTitle("   "))
        assertEquals("New chat", deriveChatTitle("123 456 ???"))
    }
}
