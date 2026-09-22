package com.neuron.ai.data.permissions

import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.permissions.PermissionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/**
 * Session-scoped permission manager. Grants last for the process lifetime;
 * dangerous capabilities always require an explicit user decision surfaced
 * through [pendingRequests], which the UI renders as a dialog.
 */
class SessionPermissionManager : PermissionManager {

    private val mutex = Mutex()
    private val _granted = MutableStateFlow(emptySet<Capability>())
    override val granted: Flow<Set<Capability>> = _granted.asStateFlow()

    private val _pending = MutableStateFlow(emptyList<PermissionRequest>())
    override val pendingRequests: Flow<List<PermissionRequest>> = _pending.asStateFlow()

    /** One completable per pending request id; completed when the user decides. */
    private val waiters = linkedMapOf<String, CompletableDeferred<Boolean>>()

    override suspend fun request(
        capability: Capability,
        reason: String,
        requestedBy: String
    ): Boolean {
        if (capability in _granted.value) return true

        val request = PermissionRequest(
            id = "perm-" + UUID.randomUUID().toString().take(8),
            capability = capability,
            reason = reason,
            requestedBy = requestedBy
        )
        val waiter = CompletableDeferred<Boolean>()

        // Register the request WITHOUT holding the mutex while waiting —
        // grant()/deny() must be able to acquire it to resolve us, otherwise
        // awaiting inside withLock would deadlock the whole permission flow.
        mutex.withLock {
            if (capability in _granted.value) return true
            _pending.value = _pending.value + request
            waiters[request.id] = waiter
        }

        try {
            return waiter.await()
        } finally {
            mutex.withLock {
                _pending.value = _pending.value.filterNot { it.id == request.id }
                waiters.remove(request.id)
            }
        }
    }

    override suspend fun grant(requestId: String) = mutex.withLock {
        _pending.value.find { it.id == requestId }?.let { request ->
            _granted.value = _granted.value + request.capability
        }
        waiters[requestId]?.complete(true)
        Unit
    }

    /** Grants only this one request without remembering the capability. */
    suspend fun grantOnce(requestId: String) = mutex.withLock {
        waiters[requestId]?.complete(true)
        Unit
    }

    override suspend fun deny(requestId: String) = mutex.withLock {
        waiters[requestId]?.complete(false)
        Unit
    }

    override suspend fun revoke(capability: Capability) = mutex.withLock {
        _granted.value = _granted.value - capability
        Unit
    }

    override suspend fun isGranted(capability: Capability): Boolean =
        capability in _granted.value
}
