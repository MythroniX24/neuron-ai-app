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
    val modelId: String?,
    /** Pinned chats float to the top of every list. */
    val pinned: Boolean = false,
    /** Workspace attached to this conversation (null = none). */
    val workspaceId: String? = null,
    /** Per-conversation Terminal capability (AI terminal access), default OFF. */
    val terminalEnabled: Boolean = false
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

/**
 * Persisted task rows so running/finished work survives process death.
 * Status is stored by enum name; unknown names decode to FAILED.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    val conversationId: String?,
    val activity: String?,
    val completedSteps: Int?,
    val totalSteps: Int?,
    val error: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
