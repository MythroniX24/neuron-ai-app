package com.neuron.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY updatedAtEpochMs DESC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    /** Atomic full refresh — the task manager persists its whole state. */
    @Transaction
    suspend fun replaceAll(tasks: List<TaskEntity>) {
        deleteAll()
        tasks.forEach { upsert(it) }
    }

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
