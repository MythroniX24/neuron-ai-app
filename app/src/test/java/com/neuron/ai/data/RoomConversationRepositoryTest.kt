package com.neuron.ai.data

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.data.conversation.RoomConversationRepository
import com.neuron.ai.data.db.ConversationDao
import com.neuron.ai.data.db.ConversationEntity
import com.neuron.ai.data.db.MessageEntity
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository contract tests. Uses a hand-written in-memory [ConversationDao]
 * so the mapping, ordering, delete-from and touch logic are exercised without
 * an Android device (Room itself is platform code).
 */
class RoomConversationRepositoryTest {

    private class FakeDao : ConversationDao {
        val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
        val messages = MutableStateFlow<List<MessageEntity>>(emptyList())

        override fun observeConversations(): Flow<List<ConversationEntity>> =
            conversations.map { list -> list.sortedByDescending { it.updatedAtEpochMs } }

        override suspend fun getConversation(id: String): ConversationEntity? =
            conversations.value.find { it.id == id }

        override fun observeConversation(id: String): Flow<ConversationEntity?> =
            conversations.map { list -> list.find { it.id == id } }

        override suspend fun upsertConversation(conversation: ConversationEntity) {
            conversations.value =
                conversations.value.filterNot { it.id == conversation.id } + conversation
        }

        override suspend fun renameConversation(id: String, title: String, updatedAt: Long) {
            conversations.value = conversations.value.map {
                if (it.id == id) it.copy(title = title, updatedAtEpochMs = updatedAt) else it
            }
        }

        override suspend fun setConversationModel(
            id: String,
            providerId: String?,
            modelId: String?,
            updatedAt: Long
        ) {
            conversations.value = conversations.value.map {
                if (it.id == id) {
                    it.copy(providerId = providerId, modelId = modelId, updatedAtEpochMs = updatedAt)
                } else {
                    it
                }
            }
        }

        override suspend fun touchConversation(id: String, updatedAt: Long) {
            conversations.value = conversations.value.map {
                if (it.id == id) it.copy(updatedAtEpochMs = updatedAt) else it
            }
        }

        override suspend fun deleteConversation(id: String) {
            conversations.value = conversations.value.filterNot { it.id == id }
        }

        override suspend fun searchConversations(query: String): List<ConversationEntity> {
            val q = query.lowercase()
            val conversationMatches = conversations.value.filter { it.title.lowercase().contains(q) }
            val messageConversationIds = messages.value
                .filter { it.content.lowercase().contains(q) }
                .map { it.conversationId }
                .toSet()
            return conversations.value
                .filter { it in conversationMatches || it.id in messageConversationIds }
                .sortedByDescending { it.updatedAtEpochMs }
        }

        override suspend fun upsertMessage(message: MessageEntity) {
            messages.value = messages.value.filterNot { it.id == message.id } + message
        }

        override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
            messages.map { list ->
                list.filter { it.conversationId == conversationId }
                    .sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
            }

        override suspend fun getMessage(id: String): MessageEntity? =
            messages.value.find { it.id == id }

        override suspend fun getMessages(conversationId: String): List<MessageEntity> =
            messages.value.filter { it.conversationId == conversationId }
                .sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))

        override suspend fun deleteMessage(id: String) {
            messages.value = messages.value.filterNot { it.id == id }
        }

        override suspend fun deleteMessagesOf(id: String) {
            messages.value = messages.value.filterNot { it.conversationId == id }
        }
    }

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private var clock = 1_000L
    private fun nextClock(): Long = clock++

    private fun repository(dao: FakeDao = FakeDao()) =
        RoomConversationRepository(
            dao = dao,
            dispatchers = dispatchers,
            json = Json { ignoreUnknownKeys = true },
            clock = ::nextClock
        )

    @Test
    fun `createConversation stores defaults and blank-title fallback`() = runTest {
        val repo = repository()

        val conversation = repo.createConversation("My chat")
        val blank = repo.createConversation("   ")

        assertEquals("My chat", repo.getConversation(conversation.id)?.title)
        assertEquals("New chat", repo.getConversation(blank.id)?.title)
        assertEquals(2, repo.conversations.first().size)
    }

    @Test
    fun `rename updates title and bumps updatedAt`() = runTest {
        val repo = repository()
        val conversation = repo.createConversation("Old title")

        repo.renameConversation(conversation.id, "Renamed")

        val stored = repo.getConversation(conversation.id)!!
        assertEquals("Renamed", stored.title)
        assertTrue(stored.updatedAtEpochMs > conversation.createdAtEpochMs)
    }

    @Test
    fun `appendMessage persists role content and metadata`() = runTest {
        val repo = repository()
        val conversation = repo.createConversation("Chat")

        val message = repo.appendMessage(
            conversationId = conversation.id,
            role = Message.Role.USER,
            content = "Hello there"
        )

        val stored = repo.messagesOf(conversation.id).first().single()
        assertEquals(message.id, stored.id)
        assertEquals(Message.Role.USER, stored.role)
        assertEquals("Hello there", stored.content)
    }

    @Test
    fun `deleteMessagesFrom removes the message and everything after it`() = runTest {
        val repo = repository()
        val conversation = repo.createConversation("Chat")
        val first = repo.appendMessage(conversation.id, Message.Role.USER, "first")
        repo.appendMessage(conversation.id, Message.Role.ASSISTANT, "second")
        val third = repo.appendMessage(conversation.id, Message.Role.USER, "third")
        repo.appendMessage(conversation.id, Message.Role.ASSISTANT, "fourth")

        repo.deleteMessagesFrom(conversation.id, third.id)

        val remaining = repo.messagesOf(conversation.id).first()
        assertEquals(listOf(first.id), remaining.map { it.id })
    }

    @Test
    fun `updateMessage rewrites content without touching other messages`() = runTest {
        val repo = repository()
        val conversation = repo.createConversation("Chat")
        repo.appendMessage(conversation.id, Message.Role.USER, "before")
        val target = repo.appendMessage(conversation.id, Message.Role.USER, "old")

        repo.updateMessage(target.id, "new content")

        val messages = repo.messagesOf(conversation.id).first()
        assertEquals("before", messages.first().content)
        assertEquals("new content", messages.last().content)
    }

    @Test
    fun `deleteConversation removes messages too`() = runTest {
        val repo = repository()
        val conversation = repo.createConversation("Chat")
        repo.appendMessage(conversation.id, Message.Role.USER, "hello")

        repo.deleteConversation(conversation.id)

        assertNull(repo.getConversation(conversation.id))
        assertTrue(repo.messagesOf(conversation.id).first().isEmpty())
    }

    @Test
    fun `search matches titles and message contents`() = runTest {
        val repo = repository()
        val titled = repo.createConversation("Kotlin notes")
        repo.appendMessage(titled.id, Message.Role.USER, "coroutines are great")
        val other = repo.createConversation("Random")
        repo.appendMessage(other.id, Message.Role.USER, "flutter widgets")

        val byTitle = repo.searchConversations("kotlin")
        val byContent = repo.searchConversations("coroutines")
        val none = repo.searchConversations("docker")

        assertEquals(listOf(titled.id), byTitle.map { it.id })
        assertEquals(listOf(titled.id), byContent.map { it.id })
        assertTrue(none.isEmpty())
        assertTrue(repo.searchConversations("").isEmpty())
    }

    @Test
    fun `setConversationModel persists provider binding`() = runTest {
        val repo = repository()
        val conversation = repo.createConversation("Chat")

        repo.setConversationModel(conversation.id, "prov-1", "gpt-test")

        val stored = repo.getConversation(conversation.id)!!
        assertEquals("prov-1", stored.providerId)
        assertEquals("gpt-test", stored.modelId)
    }
}
