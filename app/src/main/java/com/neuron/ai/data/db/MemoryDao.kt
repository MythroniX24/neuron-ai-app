package com.neuron.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Memory persistence (Milestone 3). Scope isolation enforced by callers. */
@Dao
interface MemoryDao {

    @Query("SELECT * FROM memory ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<MemoryEntryEntity>>

    @Query(
        "SELECT * FROM memory WHERE type = :type AND scopeId = :scopeId AND `key` = :key LIMIT 1"
    )
    suspend fun find(type: String, scopeId: String, key: String): MemoryEntryEntity?

    @Query(
        "SELECT * FROM memory WHERE type = :type AND (scopeId = :scopeId OR scopeId = 'global') " +
            "ORDER BY updatedAtEpochMs DESC LIMIT :limit"
    )
    suspend fun forScope(type: String, scopeId: String, limit: Int): List<MemoryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MemoryEntryEntity)

    @Query("DELETE FROM memory WHERE id = :entryId")
    suspend fun delete(entryId: String)

    @Query("DELETE FROM memory WHERE type = :type AND scopeId = :scopeId")
    suspend fun clearScope(type: String, scopeId: String)

    @Query("DELETE FROM memory")
    suspend fun clearAll()
}
