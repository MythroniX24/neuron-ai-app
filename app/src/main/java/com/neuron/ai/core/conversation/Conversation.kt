package com.neuron.ai.core.conversation

import kotlinx.coroutines.flow.Flow

/** A chat conversation. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/** A message inside a [Conversation]. */
data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val createdAtEpochMs: Long
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/** Source of truth for conversations and their messages. */
interface ConversationRepository {
    val conversations: Flow<List<Conversation>>
    fun messagesOf(conversationId: String): Flow<List<Message>>

    suspend fun createConversation(title: String): Conversation
    suspend fun appendMessage(conversationId: String, role: Message.Role, content: String): Message
    suspend fun updateMessage(messageId: String, content: String)
    suspend fun deleteConversation(conversationId: String)
}
