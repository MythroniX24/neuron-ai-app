package com.neuron.ai.data.memory

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.memory.MemoryEntry
import com.neuron.ai.core.memory.MemoryStore
import com.neuron.ai.core.memory.MemoryType
import com.neuron.ai.data.db.MemoryEntryEntity
import com.neuron.ai.data.db.NeuronDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.neuronMemoryPrefs by preferencesDataStore(name = "neuron_memory")

/**
 * Room-backed memory store (Milestone 3). Writes are secret-filtered by the
 * manager above this store; PROJECT/CONVERSATION scopes never mix. Master
 * switch lives in DataStore and defaults to ON (memory exists only for
 * explicitly-saved facts; nothing is stored automatically).
 */
class RoomMemoryStore(
    context: Context,
    database: NeuronDatabase,
    private val dispatchers: DispatcherProvider
) : MemoryStore {

    private val io = dispatchers.io
    private val dao = database.memoryDao()
    private val dataStore = context.applicationContext.neuronMemoryPrefs

    private val _enabled = MutableStateFlow(true)
    override val enabled: Flow<Boolean> = _enabled.asStateFlow()

    init {
        // Restore the master switch before any read/write decision.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io).launch {
            _enabled.value = dataStore.data.first()[KEY_ENABLED] ?: true
        }
    }

    override val entries: Flow<List<MemoryEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun put(type: MemoryType, scopeId: String, key: String, value: String): MemoryEntry =
        withContext(io) {
            checkEnabled()
            // Upsert-by-key within a scope keeps memory curated, not append-only.
            val existing = dao.find(type.name, scopeId, key)
            val now = System.currentTimeMillis()
            val entry = existing?.copy(value = value, updatedAtEpochMs = now)
                ?: MemoryEntryEntity(
                    id = "mem-" + UUID.randomUUID().toString().take(8),
                    type = type.name,
                    scopeId = scopeId,
                    key = key,
                    value = value,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            dao.upsert(entry)
            entry.toDomain()
        }

    override suspend fun get(type: MemoryType, scopeId: String, key: String): MemoryEntry? =
        withContext(io) { dao.find(type.name, scopeId, key)?.toDomain() }

    override suspend fun relevant(type: MemoryType, scopeId: String, limit: Int): List<MemoryEntry> =
        withContext(io) {
            if (!_enabled.value) emptyList()
            else dao.forScope(type.name, scopeId, limit).map { it.toDomain() }
        }

    override suspend fun delete(entryId: String) = withContext(io) { dao.delete(entryId); Unit }

    override suspend fun clearScope(type: MemoryType, scopeId: String) =
        withContext(io) { dao.clearScope(type.name, scopeId); Unit }

    override suspend fun clearAll() = withContext(io) { dao.clearAll(); Unit }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = enabled }
        _enabled.value = enabled
    }

    private fun checkEnabled() {
        check(_enabled.value) { "Memory is disabled in Settings." }
    }

    private fun MemoryEntryEntity.toDomain() = MemoryEntry(
        id = id,
        type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.PROJECT),
        scopeId = scopeId,
        key = key,
        value = value,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("memory_enabled")
    }
}
