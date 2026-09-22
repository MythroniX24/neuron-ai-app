package com.neuron.ai.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val providerId: String?,
    val modelId: String?
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAtEpochMs: Long,
    /** JSON-encoded list of attachments; decoded via kotlinx-serialization. */
    val attachmentsJson: String,
    /** JSON-encoded MessageMetadata, or null. */
    val metadataJson: String?
)
