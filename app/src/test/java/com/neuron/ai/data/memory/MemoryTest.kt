package com.neuron.ai.data.memory

import com.neuron.ai.core.memory.MemorySecretFilter
import com.neuron.ai.core.memory.MemoryStore
import com.neuron.ai.core.memory.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 memory tests: secret filtering is airtight, writes are
 * intentional-only, and recalls never cross scopes.
 */
class MemoryTest {

    /** In-memory store double mirroring RoomMemoryStore semantics. */
    private class FakeMemoryStore : MemoryStore {
        val map = linkedMapOf<String, com.neuron.ai.core.memory.MemoryEntry>()
        private val entriesFlow = MutableStateFlow<List<com.neuron.ai.core.memory.MemoryEntry>>(emptyList())
        private val enabledFlow = MutableStateFlow(true)
        private var counter = 0

        override val entries: Flow<List<com.neuron.ai.core.memory.MemoryEntry>> = entriesFlow.asStateFlow()
        override val enabled: Flow<Boolean> = enabledFlow.asStateFlow()

        private fun refresh() { entriesFlow.value = map.values.toList() }

        override suspend fun put(
            type: MemoryType, scopeId: String, key: String, value: String
        ): com.neuron.ai.core.memory.MemoryEntry {
            val existing = map.entries.find {
                it.value.type == type && it.value.scopeId == scopeId && it.value.key == key
            }
            counter++
            val entry = existing?.value?.copy(value = value)
                ?: com.neuron.ai.core.memory.MemoryEntry(
                    id = "m$counter", type = type, scopeId = scopeId, key = key,
                    value = value, createdAtEpochMs = 0, updatedAtEpochMs = 0
                )
            map[entry.id] = entry
            refresh()
            return entry
        }

        override suspend fun get(type: MemoryType, scopeId: String, key: String) =
            map.values.find { it.type == type && it.scopeId == scopeId && it.key == key }

        override suspend fun relevant(type: MemoryType, scopeId: String, limit: Int) =
            map.values.filter { it.type == type && (it.scopeId == scopeId || it.scopeId == "global") }
                .take(limit)

        override suspend fun delete(entryId: String) { map.remove(entryId); refresh() }

        override suspend fun clearScope(type: MemoryType, scopeId: String) {
            map.entries.removeAll { it.value.type == type && it.value.scopeId == scopeId }
            refresh()
        }

        override suspend fun clearAll() { map.clear(); refresh() }

        override suspend fun setEnabled(enabled: Boolean) { enabledFlow.value = enabled }
    }

    @Test
    fun `secret filter catches api keys tokens passwords and jwts`() {
        assertTrue(MemorySecretFilter.containsSecret("my key is sk-abc123def456ghi7"))
        assertTrue(MemorySecretFilter.containsSecret("password=hunter2"))
        assertTrue(MemorySecretFilter.containsSecret("Authorization: Bearer abc.def.ghi"))
        assertTrue(MemorySecretFilter.containsSecret("token: ghp_0123456789abcdefghij"))
        assertTrue(
            MemorySecretFilter.containsSecret(
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig"
            )
        )
    }

    @Test
    fun `secret filter allows normal facts`() {
        assertTrue(!MemorySecretFilter.containsSecret("User prefers Kotlin and dark coffee"))
        assertTrue(!MemorySecretFilter.containsSecret("Project uses Gradle version catalogs"))
    }

    @Test
    fun `remember rejects secrets`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        val result = manager.remember(
            MemoryType.USER_PREFERENCE, "global", "API key", "sk-abc123def456ghi7"
        )
        assertNull(result)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `remember rejects blank input and honors disable`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        assertNull(manager.remember(MemoryType.USER_PREFERENCE, "global", "", "value"))
        store.setEnabled(false)
        assertNull(
            manager.remember(MemoryType.USER_PREFERENCE, "global", "k", "v")
        )
    }

    @Test
    fun `upsert by key does not duplicate`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        manager.remember(MemoryType.USER_PREFERENCE, "global", "language", "Kotlin")
        manager.remember(MemoryType.USER_PREFERENCE, "global", "language", "Kotlin 2")
        assertEquals(1, store.map.size)
        assertEquals("Kotlin 2", store.map.values.first().value)
    }

    @Test
    fun `relevant never crosses scope`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        manager.remember(MemoryType.PROJECT, "ws-alpha", "build", "gradle")
        manager.remember(MemoryType.PROJECT, "ws-beta", "build", "maven")

        val alpha = manager.relevant(MemoryType.PROJECT, "ws-alpha", 10)
        assertTrue(alpha.all { it.scopeId == "ws-alpha" || it.scopeId == "global" })
        assertEquals(1, alpha.size)
    }

    @Test
    fun `disabled memory recalls nothing`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        manager.remember(MemoryType.USER_PREFERENCE, "global", "lang", "Kotlin")
        store.setEnabled(false)
        assertTrue(manager.relevant(MemoryType.USER_PREFERENCE, "global", 10).isEmpty())
        assertNull(manager.recall(MemoryType.USER_PREFERENCE, "global", "lang"))
    }

    /** Permission double that REFUSES everything (audit: forget must ask). */
    private class DenyingPermissions : com.neuron.ai.core.permissions.PermissionManager {
        override val pendingRequests: Flow<List<com.neuron.ai.core.permissions.PermissionRequest>> =
            MutableStateFlow(emptyList())
        override val granted: Flow<Set<com.neuron.ai.core.permissions.Capability>> =
            MutableStateFlow(emptySet())
        override val decisions: Flow<List<com.neuron.ai.core.permissions.PermissionDecision>> =
            MutableStateFlow(emptyList())
        override suspend fun request(
            capability: com.neuron.ai.core.permissions.Capability,
            reason: String,
            requestedBy: String
        ): Boolean = false
        override suspend fun grant(requestId: String) {}
        override suspend fun deny(requestId: String) {}
        override suspend fun decide(requestId: String, granted: Boolean, scope: com.neuron.ai.core.permissions.DecisionScope) {}
        override suspend fun revoke(capability: com.neuron.ai.core.permissions.Capability) {}
        override suspend fun isGranted(capability: com.neuron.ai.core.permissions.Capability): Boolean = false
        override suspend fun resetDecisions() {}
    }

    /** Permission double that APPROVES everything. */
    private class ApprovingPermissions : DenyingPermissions() {
        override suspend fun request(
            capability: com.neuron.ai.core.permissions.Capability,
            reason: String,
            requestedBy: String
        ): Boolean = true
    }

    @Test
    fun `forget tool asks first and denial prevents deletion`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        manager.remember(MemoryType.USER_PREFERENCE, "global", "lang", "Kotlin")
        val tools = MemoryManager.Tools(manager, DenyingPermissions()) { null }

        val result = tools.all.first { it.id == "memory.forget" }.execute("""{"scope":"all"}""")

        // A refusal is a distinct DENIAL and NOTHING was deleted.
        assertTrue(result is com.neuron.ai.core.agent.ToolResult.Denied)
        assertEquals(1, store.map.size)
    }

    @Test
    fun `forget tool deletes after explicit approval`() = runTest {
        val store = FakeMemoryStore()
        val manager = MemoryManager(store)
        manager.remember(MemoryType.USER_PREFERENCE, "global", "lang", "Kotlin")
        val tools = MemoryManager.Tools(manager, ApprovingPermissions()) { null }

        val result = tools.all.first { it.id == "memory.forget" }.execute("""{"scope":"all"}""")

        assertTrue(result is com.neuron.ai.core.agent.ToolResult.Success)
        assertTrue(store.map.isEmpty())
    }
}
