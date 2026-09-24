package com.neuron.ai.core.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Types of durable memory Neuron-AI keeps.
 *
 * Memory is OPT-IN and intentionally curated — never a dump of everything
 * the user says. Entries are always user-inspectable and deletable.
 */
enum class MemoryType {
    /** Durable facts about the user ("prefers Kotlin", "writes in Hinglish"). */
    USER_PREFERENCE,

    /** Facts bound to a workspace/project ("uses Gradle 8", "API base is X"). */
    PROJECT,

    /** Outcome of a finished task ("fixed the auth bug on Tuesday"). */
    TASK,

    /** Working context scoped to one conversation, cleared with it. */
    CONVERSATION
}

/** One stored memory entry. [value] is plain text; secrets are filtered out. */
@Serializable
data class MemoryEntry(
    val id: String,
    val type: MemoryType,
    /** Isolation key: workspace id for PROJECT memory, conversation id for CONVERSATION, "global" otherwise. */
    val scopeId: String,
    /** Short label the model/user can recognize ("Preferred language"). */
    val key: String,
    /** The fact itself, kept short. */
    val value: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/**
 * Long-term memory surface for agents. Reads are filtered by type/scope so
 * only RELEVANT memory enters a prompt — the whole store is never injected.
 *
 * Isolation: PROJECT memory is scoped per workspace, CONVERSATION memory per
 * conversation; neither can leak into other scopes.
 */
interface MemoryStore {
    /** All entries, newest first (UI inspection). */
    val entries: Flow<List<MemoryEntry>>

    suspend fun put(
        type: MemoryType,
        scopeId: String,
        key: String,
        value: String
    ): MemoryEntry

    /** Exact-key recall. */
    suspend fun get(type: MemoryType, scopeId: String, key: String): MemoryEntry?

    /** Relevant entries for a prompt context: matching scope + global scope. */
    suspend fun relevant(type: MemoryType, scopeId: String, limit: Int = 10): List<MemoryEntry>

    suspend fun delete(entryId: String)

    /** Deletes everything in one scope (e.g. with a workspace/conversation). */
    suspend fun clearScope(type: MemoryType, scopeId: String)

    /** Deletes ALL memory. */
    suspend fun clearAll()

    /** Master switch: when disabled, nothing is stored or recalled. */
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}

/**
 * Filters and normalizes agent-proposed memory before persistence.
 * Secrets (API keys, tokens, passwords, bearer headers…) are NEVER stored —
 * matching entries are rejected, not masked, so nothing sensitive survives.
 */
object MemorySecretFilter {

    private val secretPatterns = listOf(
        Regex("(?i)\\b(sk-[A-Za-z0-9_\\-]{8,})\\b"),                      // OpenAI-style keys
        Regex("(?i)\\b(api[_\\-]?key|apikey|secret|token|password|passwd|pwd|bearer|authorization)\\b\\s*[:=]\\s*\\S+"),
        Regex("(?i)\\b(AKIA[0-9A-Z]{12,})\\b"),                           // AWS access key
        Regex("(?i)\\b(ghp_[A-Za-z0-9]{20,})\\b"),                        // GitHub PAT
        Regex("(?i)\\b(eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{10,})\\b") // JWT
    )

    /** True when [value] looks like it contains a credential — DO NOT store. */
    fun containsSecret(value: String): Boolean =
        secretPatterns.any { it.containsMatchIn(value) }

    /** A display-safe preview for logs/UI that never shows suspect content. */
    fun redactPreview(value: String, maxChars: Int = 80): String {
        val safe = if (containsSecret(value)) "(redacted: contained a possible secret)" else value
        return safe.take(maxChars)
    }
}
