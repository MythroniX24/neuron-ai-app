package com.neuron.ai.data.conversation

import com.neuron.ai.core.conversation.Conversation
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.data.db.ConversationDao
import com.neuron.ai.data.db.MessageCodec
import com.neuron.ai.data.db.toDomain
import com.neuron.ai.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Room-backed source of truth for conversations (Phase 1).
 * All writes happen off the main dispatcher; search matches titles and contents.
 */
class RoomConversationRepository(
    private val dao: ConversationDao,
    dispatchers: DispatcherProvider,
    json: Json,
    private val clock: () -> Long = System::currentTimeMillis
) : ConversationRepository {

    private val io = dispatchers.io
    private val codec = MessageCodec(json)

    override val conversations: Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { it.toDomain() } }

    override fun messagesOf(conversationId: String): Flow<List<Message>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toDomain(codec) } }

    override suspend fun getConversation(conversationId: String): Conversation? =
        withContext(io) { dao.getConversation(conversationId)?.toDomain() }

    override suspend fun createConversation(
        title: String,
        providerId: String?,
        modelId: String?
    ): Conversation = withContext(io) {
        val now = clock()
        val conversation = Conversation(
            id = "conv-" + UUID.randomUUID().toString().take(8),
            title = title.ifBlank { "New chat" },
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            providerId = providerId,
            modelId = modelId
        )
        dao.upsertConversation(conversation.toEntity())
        conversation
    }

    override suspend fun createConversation(
        title: String,
        id: String,
        providerId: String?,
        modelId: String?
    ): Conversation = withContext(io) {
        val now = clock()
        val conversation = Conversation(
            id = id,
            title = title.ifBlank { "New chat" },
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            providerId = providerId,
            modelId = modelId
        )
        dao.upsertConversation(conversation.toEntity())
        conversation
    }

    override suspend fun renameConversation(conversationId: String, title: String) =
        withContext(io) {
            dao.renameConversation(conversationId, title.ifBlank { "Untitled" }, clock())
        }

    override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) =
        withContext(io) { dao.setPinned(conversationId, pinned) }

    override suspend fun setConversationWorkspace(conversationId: String, workspaceId: String?) =
        withContext(io) { dao.setConversationWorkspace(conversationId, workspaceId) }

    override suspend fun setConversationTerminal(conversationId: String, enabled: Boolean) =
        withContext(io) { dao.setTerminalEnabled(conversationId, enabled) }

    override suspend fun setConversationBrowser(conversationId: String, enabled: Boolean) =
        withContext(io) { dao.setBrowserEnabled(conversationId, enabled) }

    override suspend fun setConversationModel(
        conversationId: String,
        providerId: String?,
        modelId: String?
    ) = withContext(io) {
        dao.setConversationModel(conversationId, providerId, modelId, clock())
        Unit
    }

    override suspend fun appendMessage(
        conversationId: String,
        role: Message.Role,
        content: String,
        attachments: List<com.neuron.ai.core.conversation.Attachment>,
        metadata: MessageMetadata?
    ): Message = withContext(io) {
        val message = Message(
            id = "msg-" + UUID.randomUUID().toString().take(8),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAtEpochMs = clock(),
            attachments = attachments,
            metadata = metadata
        )
        dao.upsertMessage(message.toEntity(codec))
        dao.touchConversation(conversationId, clock())
        message
    }

    override suspend fun updateMessage(messageId: String, content: String, metadata: MessageMetadata?) =
        withContext(io) {
            val existing = dao.getMessage(messageId) ?: return@withContext
            val domain = existing.toDomain(codec)
            dao.upsertMessage(
                domain.copy(content = content, metadata = metadata ?: domain.metadata).toEntity(codec)
            )
            dao.touchConversation(existing.conversationId, clock())
        }

    override suspend fun deleteMessage(messageId: String) = withContext(io) {
        dao.deleteMessage(messageId)
        Unit
    }

    override suspend fun deleteConversation(conversationId: String) = withContext(io) {
        dao.deleteConversationWithMessages(conversationId)
    }

    override suspend fun searchConversations(query: String): List<Conversation> =
        withContext(io) {
            if (query.isBlank()) emptyList()
            else dao.searchConversations(query.trim()).map { it.toDomain() }
        }

    /** Deletes the given message and every newer message — used by edit-and-resend. */
    override suspend fun deleteMessagesFrom(conversationId: String, messageId: String) =
        withContext(io) {
            val all = dao.getMessages(conversationId)
            val index = all.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                all.drop(index).forEach { dao.deleteMessage(it.id) }
            }
        }
}
