package com.neuron.ai.data.permissions

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.permissions.AccessMode
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.DecisionScope
import com.neuron.ai.core.permissions.PermissionDecision
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.permissions.PermissionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Session-scoped permission manager.
 *
 * Decision model (Milestone 1):
 * - SAFE tools  → capabilities can be remembered and auto-granted.
 * - ELEVATED    → grants default to SESSION scope; the user may narrow them.
 * - DESTRUCTIVE → a dialog is ALWAYS shown; nothing is ever remembered, so
 *   destructive operations can never execute silently.
 */
class SessionPermissionManager : PermissionManager {

    private val mutex = Mutex()
    private val _granted = MutableStateFlow(emptySet<Capability>())
    override val granted: Flow<Set<Capability>> = _granted.asStateFlow()

    private val _pending = MutableStateFlow(emptyList<PermissionRequest>())
    override val pendingRequests: Flow<List<PermissionRequest>> = _pending.asStateFlow()

    /** Remembered decisions: (capability, workspace) → mode, for review/reset. */
    private val _decisions =
        MutableStateFlow(emptyList<PermissionDecision>())
    override val decisions: Flow<List<PermissionDecision>> = _decisions.asStateFlow()

    /** Durable DENY memory: denied keys auto-reject future requests. */
    private val deniedKeys = MutableStateFlow(emptySet<String>())

    /** One completable per pending request id; completed when the user decides. */
    private val waiters = linkedMapOf<String, CompletableDeferred<Boolean>>()

    override suspend fun request(
        capability: Capability,
        reason: String,
        requestedBy: String
    ): Boolean = request(capability, reason, requestedBy, RiskLevel.SAFE, null)

    override suspend fun request(
        capability: Capability,
        reason: String,
        requestedBy: String,
        riskLevel: RiskLevel,
        workspaceId: String?
    ): Boolean {
        // DESTRUCTIVE operations must ALWAYS surface a dialog — never silent.
        val destructive = riskLevel == RiskLevel.DESTRUCTIVE ||
            capability == Capability.FILESYSTEM_DELETE

        if (!destructive && capability in _granted.value) return true

        val key = decisionKey(capability, workspaceId)
        if (!destructive && key in deniedKeys.value) return false

        val request = PermissionRequest(
            id = "perm-" + UUID.randomUUID().toString().take(8),
            capability = capability,
            reason = reason,
            requestedBy = requestedBy,
            riskLevel = riskLevel,
            workspaceId = workspaceId
        )
        val waiter = CompletableDeferred<Boolean>()

        // Register the request WITHOUT holding the mutex while waiting —
        // grant()/deny() must be able to acquire it to resolve us, otherwise
        // awaiting inside withLock would deadlock the whole permission flow.
        mutex.withLock {
            if (!destructive && capability in _granted.value) return true
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

    /** Allow for the rest of the process (session scope). */
    override suspend fun grant(requestId: String) = decide(requestId, granted = true, scope = DecisionScope.SESSION)

    override suspend fun deny(requestId: String) = decide(requestId, granted = false, scope = DecisionScope.ONCE)

    override suspend fun decide(requestId: String, granted: Boolean, scope: DecisionScope) {
        val resolved: PermissionRequest? = mutex.withLock {
            val request = _pending.value.find { it.id == requestId }
            if (request != null) {
                applyScope(request, granted, scope)
                waiters[requestId]?.complete(granted)
            }
            request
        }
        // Completing outside the lock is fine: the awaiter only cleans up.
        if (resolved == null) {
            // Unknown/expired request — complete nothing; caller UI already gone.
        }
    }

    private suspend fun applyScope(request: PermissionRequest, granted: Boolean, scope: DecisionScope) {
        val destructive = request.riskLevel == RiskLevel.DESTRUCTIVE ||
            request.capability == Capability.FILESYSTEM_DELETE
        // ONCE (and anything on DESTRUCTIVE) leaves no memory behind.
        if (scope == DecisionScope.ONCE || destructive) return

        when (scope) {
            DecisionScope.SESSION -> {
                if (granted) _granted.value = _granted.value + request.capability
                else deniedKeys.value = deniedKeys.value + decisionKey(request.capability, request.workspaceId)
            }
            DecisionScope.WORKSPACE -> {
                if (granted) _granted.value = _granted.value + request.capability
                else deniedKeys.value = deniedKeys.value + decisionKey(request.capability, request.workspaceId)
                rememberDecision(request, granted)
            }
            DecisionScope.ALWAYS -> {
                if (granted) _granted.value = _granted.value + request.capability
                else deniedKeys.value = deniedKeys.value + decisionKey(request.capability, request.workspaceId)
                rememberDecision(request, granted)
            }
            DecisionScope.ONCE -> Unit // unreachable; handled above
        }
    }

    private suspend fun rememberDecision(request: PermissionRequest, granted: Boolean) {
        val decision = PermissionDecision(
            capability = request.capability,
            workspaceId = request.workspaceId,
            mode = if (granted) AccessMode.ALLOW else AccessMode.DENY
        )
        _decisions.value =
            _decisions.value.filterNot {
                it.capability == decision.capability && it.workspaceId == decision.workspaceId
            } + decision
    }

    private fun decisionKey(capability: Capability, workspaceId: String?) =
        "${capability.name}:${workspaceId ?: "-"}"

    override suspend fun revoke(capability: Capability) = mutex.withLock {
        _granted.value = _granted.value - capability
        deniedKeys.value = deniedKeys.value.filterNot { it.startsWith("${capability.name}:") }.toSet()
        _decisions.value = _decisions.value.filterNot { it.capability == capability }
        Unit
    }

    override suspend fun isGranted(capability: Capability): Boolean =
        capability in _granted.value

    override suspend fun resetDecisions() = mutex.withLock {
        _granted.value = emptySet()
        deniedKeys.value = emptySet()
        _decisions.value = emptyList()
        Unit
    }
}
