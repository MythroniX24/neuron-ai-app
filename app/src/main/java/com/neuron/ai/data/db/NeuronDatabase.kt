package com.neuron.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NeuronDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var instance: NeuronDatabase? = null

        fun get(context: Context): NeuronDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeuronDatabase::class.java,
                    "neuron.db"
                ).build().also { instance = it }
            }
    }
}
