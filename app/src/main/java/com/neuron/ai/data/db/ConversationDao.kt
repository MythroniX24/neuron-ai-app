package com.neuron.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        "SELECT * FROM conversations ORDER BY pinned DESC, updatedAtEpochMs DESC"
    )
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, updatedAt: Long)

    @Query(
        "UPDATE conversations SET providerId = :providerId, modelId = :modelId, " +
            "updatedAtEpochMs = :updatedAt WHERE id = :id"
    )
    suspend fun setConversationModel(
        id: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Long
    )

    @Query("UPDATE conversations SET updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET workspaceId = :workspaceId WHERE id = :id")
    suspend fun setConversationWorkspace(id: String, workspaceId: String?)

    @Query("UPDATE conversations SET terminalEnabled = :enabled WHERE id = :id")
    suspend fun setTerminalEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)    @Query("SELECT DISTINCT conversations.* FROM conversations " +
            "JOIN messages ON messages.conversationId = conversations.id " +
            "WHERE conversations.title LIKE '%' || :query || '%' " +
            "OR messages.content LIKE '%' || :query || '%' " +
            "ORDER BY conversations.pinned DESC, conversations.updatedAtEpochMs DESC")
    suspend fun searchConversations(query: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC, rowid ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC, rowid ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Transaction
    suspend fun deleteConversationWithMessages(id: String) {
        deleteMessagesOf(id)
        deleteConversation(id)
    }

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun deleteMessagesOf(id: String)
}
