package com.neuron.ai.data.db

import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.core.conversation.Conversation
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON codec for the blobs stored inside message rows. */
internal class MessageCodec(private val json: Json) {

    fun encodeAttachments(attachments: List<Attachment>): String =
        if (attachments.isEmpty()) "[]"
        else json.encodeToString<List<Attachment>>(attachments)

    fun decodeAttachments(raw: String?): List<Attachment> = runCatching {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString<List<Attachment>>(raw)
    }.getOrDefault(emptyList())

    fun encodeMetadata(metadata: MessageMetadata?): String? =
        metadata?.let { runCatching { json.encodeToString<MessageMetadata>(it) }.getOrNull() }

    fun decodeMetadata(raw: String?): MessageMetadata? = runCatching {
        raw?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<MessageMetadata>(it) }
    }.getOrNull()
}

internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    providerId = providerId,
    modelId = modelId,
    pinned = pinned
)

internal fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    providerId = providerId,
    modelId = modelId,
    pinned = pinned
)

internal fun MessageEntity.toDomain(codec: MessageCodec): Message = Message(
    id = id,
    conversationId = conversationId,
    role = runCatching { Message.Role.valueOf(role) }.getOrDefault(Message.Role.USER),
    content = content,
    createdAtEpochMs = createdAtEpochMs,
    attachments = codec.decodeAttachments(attachmentsJson),
    metadata = codec.decodeMetadata(metadataJson)
)

internal fun Message.toEntity(codec: MessageCodec): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    createdAtEpochMs = createdAtEpochMs,
    attachmentsJson = codec.encodeAttachments(attachments),
    metadataJson = codec.encodeMetadata(metadata)
)
