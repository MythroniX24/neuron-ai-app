package com.neuron.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, TaskEntity::class, MemoryEntryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class NeuronDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao

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

        /** v2 → v3: workspace binding + terminal capability on conversations. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN workspaceId TEXT DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN terminalEnabled INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3 → v4: browser capability + memory table (Milestone 3). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN browserEnabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "type TEXT NOT NULL, " +
                        "scopeId TEXT NOT NULL, " +
                        "key TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, " +
                        "updatedAtEpochMs INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_type_scopeId ON memory (type, scopeId)"
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
