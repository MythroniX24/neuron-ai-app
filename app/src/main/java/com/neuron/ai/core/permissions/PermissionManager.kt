package com.neuron.ai.core.permissions

import kotlinx.coroutines.flow.Flow

/**
 * Runtime permission requests raised by tools or agents.
 * Android system permissions (storage, camera…) map onto the same mechanism.
 */
enum class Capability {
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    NETWORK,
    BROWSER,
    TERMINAL,
    NOTIFICATIONS
}

/** A request awaiting a user decision. */
data class PermissionRequest(
    val id: String,
    val capability: Capability,
    val reason: String,
    val requestedBy: String
)

/** Grants/revokes capabilities and surfaces pending requests to the UI. */
interface PermissionManager {
    val pendingRequests: Flow<List<PermissionRequest>>
    val granted: Flow<Set<Capability>>

    /**
     * Suspends until the user decides. Returns true when granted.
     * Implementations may auto-grant safe capabilities.
     */
    suspend fun request(
        capability: Capability,
        reason: String,
        requestedBy: String
    ): Boolean

    suspend fun grant(requestId: String)
    suspend fun deny(requestId: String)
    suspend fun revoke(capability: Capability)
    suspend fun isGranted(capability: Capability): Boolean
}
