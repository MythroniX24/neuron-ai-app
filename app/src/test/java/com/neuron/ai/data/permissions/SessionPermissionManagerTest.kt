package com.neuron.ai.data.permissions

import com.neuron.ai.core.permissions.Capability
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies grant/deny flow and session-scoped persistence of capabilities. */
class SessionPermissionManagerTest {

    @Test
    fun `request suspends until granted`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.FILESYSTEM_READ, "read a file", "fs.read") }

        // The request should appear as pending while suspended.
        withTimeout(2_000) {
            while (manager.pendingRequests.first().isEmpty()) kotlinx.coroutines.delay(10)
        }
        val request = manager.pendingRequests.first().single()
        assertEquals(Capability.FILESYSTEM_READ, request.capability)
        assertEquals("fs.read", request.requestedBy)

        manager.grant(request.id)
        assertTrue(decision.await())
        assertTrue(manager.isGranted(Capability.FILESYSTEM_READ))
    }

    @Test
    fun `deny resolves the request as false without granting`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.NETWORK, "call web", "web.tool") }

        withTimeout(2_000) {
            while (manager.pendingRequests.first().isEmpty()) kotlinx.coroutines.delay(10)
        }
        manager.deny(manager.pendingRequests.first().single().id)

        assertFalse(decision.await())
        assertFalse(manager.isGranted(Capability.NETWORK))
        assertTrue(manager.pendingRequests.value.isEmpty())
    }

    @Test
    fun `granted capability short-circuits future requests`() = runTest {
        val manager = SessionPermissionManager()
        manager.grant("anything") // no-op for unknown id

        // First request must be granted manually…
        val decision = async { manager.request(Capability.TERMINAL, "run cmd", "terminal") }
        withTimeout(2_000) {
            while (manager.pendingRequests.first().isEmpty()) kotlinx.coroutines.delay(10)
        }
        manager.grant(manager.pendingRequests.first().single().id)
        assertTrue(decision.await())

        // …then subsequent requests resolve immediately without a new prompt.
        assertTrue(manager.request(Capability.TERMINAL, "run again", "terminal"))
        assertTrue(manager.pendingRequests.first().isEmpty())
    }

    @Test
    fun `revoke removes a granted capability`() = runTest {
        val manager = SessionPermissionManager()
        val decision = async { manager.request(Capability.BROWSER, "open page", "browser") }
        withTimeout(2_000) {
            while (manager.pendingRequests.first().isEmpty()) kotlinx.coroutines.delay(10)
        }
        manager.grant(manager.pendingRequests.first().single().id)
        assertTrue(manager.isGranted(Capability.BROWSER))

        manager.revoke(Capability.BROWSER)
        assertFalse(manager.isGranted(Capability.BROWSER))
    }
}
