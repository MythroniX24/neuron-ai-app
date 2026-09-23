package com.neuron.ai.data.permissions

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.DecisionScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 1 scoped decisions: once / session / workspace / always-deny. */
class SessionPermissionScopeTest {

    private suspend fun awaitPending(manager: SessionPermissionManager) =
        withTimeout(2_000) {
            while (manager.pendingRequests.first().isEmpty()) kotlinx.coroutines.delay(10)
            manager.pendingRequests.first().single()
        }

    @Test
    fun `allow once does not remember the capability`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.EXECUTE, "run", "tool") }
        val request = awaitPending(manager)

        manager.decide(request.id, granted = true, scope = DecisionScope.ONCE)

        assertTrue(decision.await())
        assertFalse(manager.isGranted(Capability.EXECUTE))
        assertTrue(manager.decisions.first().isEmpty())
    }

    @Test
    fun `allow for session remembers and auto-grants later`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.EXECUTE, "run", "tool") }
        val request = awaitPending(manager)

        manager.decide(request.id, granted = true, scope = DecisionScope.SESSION)

        assertTrue(decision.await())
        assertTrue(manager.isGranted(Capability.EXECUTE))
        // Next request resolves instantly without any dialog.
        assertTrue(manager.request(Capability.EXECUTE, "again", "tool"))
    }

    @Test
    fun `always deny auto-rejects future requests`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.NETWORK, "web", "tool") }
        val request = awaitPending(manager)

        manager.decide(request.id, granted = false, scope = DecisionScope.ALWAYS)

        assertFalse(decision.await())
        assertFalse(manager.request(Capability.NETWORK, "again", "tool"))
        assertEquals(1, manager.decisions.first().size)
    }

    @Test
    fun `destructive risk always prompts regardless of stored grants`() = runTest {
        val manager = SessionPermissionManager()
        // First request granted for session…
        val first = async { manager.request(Capability.FILESYSTEM_DELETE, "del", "tool", RiskLevel.DESTRUCTIVE) }
        val firstRequest = awaitPending(manager)
        manager.decide(firstRequest.id, granted = true, scope = DecisionScope.SESSION)
        assertTrue(first.await())

        // …but a second destructive request must prompt AGAIN.
        val second = async { manager.request(Capability.FILESYSTEM_DELETE, "del", "tool", RiskLevel.DESTRUCTIVE) }
        awaitPending(manager) // must surface a new dialog instead of auto-granting
        manager.deny(manager.pendingRequests.first().single().id)
        assertFalse(second.await())
    }

    @Test
    fun `resetDecisions clears everything`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.TERMINAL, "cmd", "tool") }
        val request = awaitPending(manager)
        manager.decide(request.id, granted = true, scope = DecisionScope.WORKSPACE)

        manager.resetDecisions()

        assertFalse(manager.isGranted(Capability.TERMINAL))
        assertTrue(manager.decisions.first().isEmpty())
    }
}
