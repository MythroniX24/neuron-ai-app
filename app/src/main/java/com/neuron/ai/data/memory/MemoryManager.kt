package com.neuron.ai.data.memory

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.memory.MemoryEntry
import com.neuron.ai.core.memory.MemorySecretFilter
import com.neuron.ai.core.memory.MemoryStore
import com.neuron.ai.core.memory.MemoryType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Curation layer over [MemoryStore] (Milestone 3): only intentionally
 * selected facts are stored, secrets are rejected, and recalls are always
 * scope-filtered. The agent reaches memory exclusively through [tools] —
 * it can never bulk-dump entries or read another conversation's scope.
 */
class MemoryManager(private val store: MemoryStore) {

    /** Proposes a memory write; returns null when rejected (disabled/secret/blank). */
    suspend fun remember(
        type: MemoryType,
        scopeId: String,
        key: String,
        value: String
    ): MemoryEntry? {
        if (!store.enabled.first()) return null
        if (key.isBlank() || value.isBlank()) return null
        // Secrets never persist — reject, don't mask.
        if (MemorySecretFilter.containsSecret(key) || MemorySecretFilter.containsSecret(value)) {
            return null
        }
        return store.put(type, scopeId, key.take(80), value.take(400))
    }

    suspend fun recall(type: MemoryType, scopeId: String, key: String): MemoryEntry? {
        if (!store.enabled.first()) return null
        return store.get(type, scopeId, key)
    }

    /** Relevant entries for a prompt, capped. */
    suspend fun relevant(type: MemoryType, scopeId: String, limit: Int = 10): List<MemoryEntry> {
        if (!store.enabled.first()) return emptyList()
        return store.relevant(type, scopeId, limit)
    }

    suspend fun delete(entryId: String) = store.delete(entryId)
    suspend fun clearAll() = store.clearAll()

    val entries get() = store.entries
    val enabled get() = store.enabled
    suspend fun setEnabled(enabled: Boolean) = store.setEnabled(enabled)

    /** True when the rejected value contained a secret (for tool messaging). */
    fun isSecret(value: String): Boolean = MemorySecretFilter.containsSecret(value)

    /**
     * Agent-facing memory tools. [scopeProvider] resolves the CURRENT
     * conversation's workspace scope at execution time — the model can never
     * choose a scope, so cross-workspace leakage is impossible by design.
     */
    class Tools(
        private val manager: MemoryManager,
        /** Resolves the active workspace id (or null) for PROJECT-scoped memory. */
        private val workspaceScopeProvider: suspend () -> String?
    ) {

        private val json = Json { ignoreUnknownKeys = true }

        private fun str(parsed: kotlinx.serialization.json.JsonObject, k: String): String? =
            runCatching { (parsed[k] as JsonPrimitive).content }.getOrNull()

        private suspend fun projectScope(): String? =
            workspaceScopeProvider()

        inner class Remember : Tool {
            override val id = "memory.remember"
            override val title = "Saving memory"
            override val description =
                "Saves a durable fact the user explicitly asked to remember (preference or " +
                    "project fact). Never store credentials or secrets. Use sparingly."
            // Local, user-scoped data only — no special capability needed;
            // DESTRUCTIVE risk on Forget still forces a confirmation dialog.
            override val requiredCapabilities = emptySet<com.neuron.ai.core.permissions.Capability>()
            override val riskLevel = RiskLevel.SAFE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"type":{"type":"string","enum":["USER_PREFERENCE","PROJECT"]},"key":{"type":"string"},"value":{"type":"string"}},"required":["type","key","value"]}"""

            override suspend fun execute(argumentsJson: String): ToolResult {
                val parsed = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
                    ?: return ToolResult.Failure("Invalid arguments.")
                val type = when (str(parsed, "type")) {
                    "USER_PREFERENCE" -> MemoryType.USER_PREFERENCE
                    "PROJECT" -> MemoryType.PROJECT
                    else -> return ToolResult.Failure("type must be USER_PREFERENCE or PROJECT")
                }
                val key = str(parsed, "key")
                    ?: return ToolResult.Failure("Missing key")
                val value = str(parsed, "value")
                    ?: return ToolResult.Failure("Missing value")

                val scope = if (type == MemoryType.PROJECT) {
                    projectScope() ?: return ToolResult.Failure(
                        "PROJECT memory needs a workspace attached to this chat (+ → Workspace)."
                    )
                } else {
                    SCOPE_GLOBAL
                }
                val entry = manager.remember(type, scope, key, value)
                    ?: return ToolResult.Failure(
                        when {
                            manager.isSecret(value) || manager.isSecret(key) ->
                                "Refused: that looks like a secret. Secrets are never stored in memory."
                            else -> "Could not store this memory (disabled in Settings, or blank)."
                        }
                    )
                return ToolResult.Success(
                    "Remembered (${entry.type.name.lowercase().replace('_', ' ')}): ${entry.key}"
                )
            }
        }

        inner class Recall : Tool {
            override val id = "memory.recall"
            override val title = "Recalling memory"
            override val description =
                "Lists saved memories for this chat: user preferences and, when a workspace is " +
                    "attached, that workspace's project memory (top 10 each)."
            override val requiredCapabilities = emptySet<com.neuron.ai.core.permissions.Capability>()
            override val riskLevel = RiskLevel.SAFE
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""

            override suspend fun execute(argumentsJson: String): ToolResult {
                val prefs = manager.relevant(MemoryType.USER_PREFERENCE, SCOPE_GLOBAL, 10)
                val project = projectScope()
                    ?.let { manager.relevant(MemoryType.PROJECT, it, 10) }
                    .orEmpty()
                if (prefs.isEmpty() && project.isEmpty()) {
                    return ToolResult.Success("No memories stored yet.")
                }
                val sb = StringBuilder()
                if (prefs.isNotEmpty()) sb.append("PREFERENCES:\n")
                prefs.forEach { sb.append("- ${it.key}: ${it.value}\n") }
                if (project.isNotEmpty()) sb.append("\nPROJECT:\n")
                project.forEach { sb.append("- ${it.key}: ${it.value}\n") }
                return ToolResult.Success(sb.toString().trim())
            }
        }

        inner class Forget : Tool {
            override val id = "memory.forget"
            override val title = "Deleting memory"
            override val description =
                "Deletes saved memory: one entry by key, or ALL memory. Always asks for confirmation."
            override val requiredCapabilities = emptySet<com.neuron.ai.core.permissions.Capability>()
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override val parametersSchemaJson =
                """{"type":"object","properties":{"scope":{"type":"string","enum":["preference","project","all"]},"key":{"type":"string"}},"required":["scope"]}"""

            override suspend fun execute(argumentsJson: String): ToolResult {
                val parsed = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
                    ?: return ToolResult.Failure("Invalid arguments.")
                val scope = str(parsed, "scope")
                    ?: return ToolResult.Failure("Missing scope")
                val key = str(parsed, "key")

                when (scope) {
                    "all" -> manager.clearAll()
                    "preference" -> {
                        val keyArg = key ?: return ToolResult.Failure("Missing key")
                        val entry = manager.recall(MemoryType.USER_PREFERENCE, SCOPE_GLOBAL, keyArg)
                            ?: return ToolResult.Failure("No memory \"$keyArg\".")
                        manager.delete(entry.id)
                    }
                    "project" -> {
                        val wsId = projectScope()
                            ?: return ToolResult.Failure("No workspace attached to this chat.")
                        val keyArg = key ?: return ToolResult.Failure("Missing key")
                        val entry = manager.recall(MemoryType.PROJECT, wsId, keyArg)
                            ?: return ToolResult.Failure("No memory \"$keyArg\".")
                        manager.delete(entry.id)
                    }
                    else -> return ToolResult.Failure("Unknown scope.")
                }
                return ToolResult.Success("Forgotten ($scope).")
            }
        }

        val all: List<Tool> get() = listOf(Remember(), Recall(), Forget())
    }
}

/** USER_PREFERENCE memory lives in the shared global scope. */
/** Scope id for user-global (non-workspace) memory; visible for wiring. */
const val SCOPE_GLOBAL = "global"
