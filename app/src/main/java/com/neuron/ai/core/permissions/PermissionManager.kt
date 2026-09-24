package com.neuron.ai.core.permissions

import kotlinx.coroutines.flow.Flow

/**
 * Runtime permission requests raised by tools or agents.
 * Android system permissions (storage, camera…) map onto the same mechanism.
 */
enum class Capability {
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    FILESYSTEM_DELETE,
    EXECUTE,
    NETWORK,
    BROWSER,
    TERMINAL,
    NOTIFICATIONS,
    /** User-confirmed memory deletion (curated long-term data). */
    MEMORY
}

/** How a capability may be granted. */
enum class AccessMode { ALLOW, DENY, ASK }

/** Scope of a granted/denied decision. */
enum class DecisionScope { ONCE, SESSION, WORKSPACE, ALWAYS }

/** A request awaiting a user decision. */
data class PermissionRequest(
    val id: String,
    val capability: Capability,
    val reason: String,
    val requestedBy: String,
    val riskLevel: com.neuron.ai.core.agent.RiskLevel = com.neuron.ai.core.agent.RiskLevel.SAFE,
    /** Optional workspace binding for workspace-scoped decisions. */
    val workspaceId: String? = null
)

/** A durable permission decision the UI can inspect and reset. */
data class PermissionDecision(
    val capability: Capability,
    val workspaceId: String?,
    val mode: AccessMode
)

/**
 * Grants/revokes capabilities and surfaces pending requests to the UI.
 *
 * Semantics:
 * - ALLOW (remembered for the requested scope) → later requests auto-grant.
 * - DENY  (remembered for the requested scope) → later requests auto-deny.
 * - ASK   → the user is prompted now; ONCE means nothing is remembered.
 *
 * DESTRUCTIVE work is never remembered silently: tools carrying the
 * FILESYSTEM_DELETE capability must request it explicitly each time.
 */
interface PermissionManager {
    val pendingRequests: Flow<List<PermissionRequest>>
    val granted: Flow<Set<Capability>>

    /**
     * Suspends until the user decides (or a stored decision answers).
     * Returns true when granted.
     */
    suspend fun request(
        capability: Capability,
        reason: String,
        requestedBy: String
    ): Boolean

    /** Risk-aware variant used by the tool executor. */
    suspend fun request(
        capability: Capability,
        reason: String,
        requestedBy: String,
        riskLevel: com.neuron.ai.core.agent.RiskLevel,
        workspaceId: String? = null
    ): Boolean = request(capability, reason, requestedBy)

    suspend fun grant(requestId: String)
    suspend fun deny(requestId: String)

    /** Applies a scoped decision to a pending request (Allow/Deny once/session/workspace/always). */
    suspend fun decide(requestId: String, granted: Boolean, scope: DecisionScope)

    suspend fun revoke(capability: Capability)
    suspend fun isGranted(capability: Capability): Boolean

    /** User-facing review: every remembered ALLOW/DENY decision. */
    val decisions: Flow<List<PermissionDecision>>
    suspend fun resetDecisions()
}
