package com.neuron.ai.data

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.data.conversation.InMemoryConversationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryConversationRepositoryTest {

    private lateinit var repository: InMemoryConversationRepository
    private var fakeTime = 1_000L

    @Before
    fun setUp() {
        repository = InMemoryConversationRepository(clock = { fakeTime })
    }

    @Test
    fun `createConversation stores and exposes conversation`() = runTest {
        val conversation = repository.createConversation("Test chat")

        val all = repository.conversations.first()
        assertEquals(listOf(conversation), all)
        assertEquals("Test chat", all.first().title)
    }

    @Test
    fun `appendMessage adds message and updates timestamp`() = runTest {
        val conversation = repository.createConversation("Chat")
        fakeTime = 2_000L

        repository.appendMessage(conversation.id, Message.Role.USER, "Hello")

        val messages = repository.messagesOf(conversation.id).first()
        assertEquals(1, messages.size)
        assertEquals(Message.Role.USER, messages.first().role)
        assertEquals("Hello", messages.first().content)

        val updated = repository.conversations.first().first()
        assertEquals(2_000L, updated.updatedAtEpochMs)
    }

    @Test
    fun `messagesOf isolates conversations`() = runTest {
        val a = repository.createConversation("A")
        val b = repository.createConversation("B")

        repository.appendMessage(a.id, Message.Role.USER, "for a")
        repository.appendMessage(b.id, Message.Role.USER, "for b")

        assertEquals("for a", repository.messagesOf(a.id).first().single().content)
        assertEquals("for b", repository.messagesOf(b.id).first().single().content)
    }

    @Test
    fun `updateMessage replaces content`() = runTest {
        val conversation = repository.createConversation("Chat")
        val message = repository.appendMessage(conversation.id, Message.Role.USER, "old")

        repository.updateMessage(message.id, "new")

        assertEquals("new", repository.messagesOf(conversation.id).first().single().content)
    }

    @Test
    fun `deleteConversation removes conversation and messages`() = runTest {
        val conversation = repository.createConversation("Chat")
        repository.appendMessage(conversation.id, Message.Role.USER, "hello")

        repository.deleteConversation(conversation.id)

        assertTrue(repository.conversations.first().isEmpty())
        assertTrue(repository.messagesOf(conversation.id).first().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `appendMessage to unknown conversation fails`() = runTest {
        repository.appendMessage("conv-404", Message.Role.USER, "hello")
    }
}
