package com.neuron.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, TaskEntity::class],
    version = 2,
    exportSchema = false
)
abstract class NeuronDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var instance: NeuronDatabase? = null

        /** v1 → v2: pinned flag on conversations + new tasks table. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tasks (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "conversationId TEXT, " +
                        "activity TEXT, " +
                        "completedSteps INTEGER, " +
                        "totalSteps INTEGER, " +
                        "error TEXT, " +
                        "createdAtEpochMs INTEGER NOT NULL, " +
                        "updatedAtEpochMs INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): NeuronDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeuronDatabase::class.java,
                    "neuron.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
