package com.neuron.ai.core.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A file attached to a message. Content lives in app-private storage;
 * only metadata is persisted in the database.
 */
@Serializable
data class Attachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Path inside app-private storage; never a raw shared-storage path. */
    val localPath: String,
    val kind: Kind
) {
    enum class Kind { TEXT, IMAGE, PDF, BINARY }

    val isImage: Boolean get() = kind == Kind.IMAGE
    val isText: Boolean get() = kind == Kind.TEXT
}

/** Optional, transport-agnostic facts about how a message was produced. */
@Serializable
data class MessageMetadata(
    val providerId: String? = null,
    val modelId: String? = null,
    val isError: Boolean = false,
    val generationMs: Long? = null,
    /** For TOOL-role messages: the call id this message answers. */
    val toolCallId: String? = null,
    val toolName: String? = null
)

/** A chat conversation. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Provider/model this conversation is bound to (null = app default). */
    val providerId: String? = null,
    val modelId: String? = null
)

/** A message inside a [Conversation]. */
data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val createdAtEpochMs: Long,
    val attachments: List<Attachment> = emptyList(),
    val metadata: MessageMetadata? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
}

/** Source of truth for conversations and their messages. */
interface ConversationRepository {
    val conversations: Flow<List<Conversation>>
    fun messagesOf(conversationId: String): Flow<List<Message>>

    suspend fun getConversation(conversationId: String): Conversation?
    suspend fun createConversation(
        title: String,
        providerId: String? = null,
        modelId: String? = null
    ): Conversation

    suspend fun renameConversation(conversationId: String, title: String)
    suspend fun setConversationModel(conversationId: String, providerId: String?, modelId: String?)

    suspend fun appendMessage(
        conversationId: String,
        role: Message.Role,
        content: String,
        attachments: List<Attachment> = emptyList(),
        metadata: MessageMetadata? = null
    ): Message

    suspend fun updateMessage(messageId: String, content: String, metadata: MessageMetadata? = null)
    suspend fun deleteMessage(messageId: String)

    /** Deletes [messageId] and every newer message in its conversation (edit-and-resend). */
    suspend fun deleteMessagesFrom(conversationId: String, messageId: String)

    suspend fun deleteConversation(conversationId: String)

    /** Case-insensitive search over titles and message contents. */
    suspend fun searchConversations(query: String): List<Conversation>
}
