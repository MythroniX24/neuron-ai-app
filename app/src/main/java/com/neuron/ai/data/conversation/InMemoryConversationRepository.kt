package com.neuron.ai.data.conversation

import com.neuron.ai.core.conversation.Conversation
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.conversation.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 0 conversation store: process-lifetime in-memory state with correct
 * reactive semantics. Phase 1 replaces this with a Room-backed implementation
 * behind the same [ConversationRepository] interface.
 */
class InMemoryConversationRepository(
    private val clock: () -> Long = System::currentTimeMillis
) : ConversationRepository {

    private val mutex = Mutex()

    private val _conversations =
        MutableStateFlow<List<Conversation>>(emptyList())
    private val messagesByConversation =
        MutableStateFlow<Map<String, List<Message>>>(emptyMap())

    private var nextId = 1

    private fun newId(prefix: String): String = "$prefix-${nextId++}"

    override val conversations: Flow<List<Conversation>> = _conversations.asStateFlow()

    override fun messagesOf(conversationId: String): Flow<List<Message>> =
        messagesByConversation.map { it[conversationId].orEmpty() }

    override suspend fun createConversation(title: String): Conversation =
        mutex.withLock {
            val now = clock()
            val conversation = Conversation(
                id = newId("conv"),
                title = title,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            _conversations.value = _conversations.value + conversation
            messagesByConversation.value =
                messagesByConversation.value + (conversation.id to emptyList())
            conversation
        }

    override suspend fun appendMessage(
        conversationId: String,
        role: Message.Role,
        content: String
    ): Message = mutex.withLock {
        requireConversation(conversationId)
        val message = Message(
            id = newId("msg"),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAtEpochMs = clock()
        )
        messagesByConversation.value =
            messagesByConversation.value + (conversationId to (messagesByConversation.value[conversationId].orEmpty() + message))
        touch(conversationId)
        message
    }

    override suspend fun updateMessage(messageId: String, content: String) =
        mutex.withLock {
            val all = messagesByConversation.value
            val conversationId = all.entries
                .firstOrNull { (_, messages) -> messages.any { it.id == messageId } }
                ?.key
                ?: return@withLock

            val updated = all.getValue(conversationId).map { message ->
                if (message.id == messageId) message.copy(content = content) else message
            }
            messagesByConversation.value = all + (conversationId to updated)
            touch(conversationId)
        }

    override suspend fun deleteConversation(conversationId: String) = mutex.withLock {
        _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        messagesByConversation.value =
            messagesByConversation.value - conversationId
    }

    private fun requireConversation(conversationId: String) {
        require(_conversations.value.any { it.id == conversationId }) {
            "Unknown conversation: $conversationId"
        }
    }

    private fun touch(conversationId: String) {
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(updatedAtEpochMs = clock())
            } else {
                conversation
            }
        }
    }
}
