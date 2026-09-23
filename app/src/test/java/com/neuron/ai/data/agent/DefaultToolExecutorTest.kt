package com.neuron.ai.data.agent

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.DecisionScope
import com.neuron.ai.core.permissions.PermissionDecision
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.permissions.PermissionRequest
import com.neuron.ai.data.tool.InMemoryToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies permission gating, retry and timeout behavior of the executor. */
class DefaultToolExecutorTest {

    private class FakePermissions(private val grant: Boolean = true) : PermissionManager {
        val requested = mutableListOf<Capability>()
        override val pendingRequests: Flow<List<PermissionRequest>> = MutableStateFlow(emptyList())
        override val granted: Flow<Set<Capability>> = MutableStateFlow(emptySet())
        override val decisions: Flow<List<PermissionDecision>> = MutableStateFlow(emptyList())
        override suspend fun request(
            capability: Capability,
            reason: String,
            requestedBy: String
        ): Boolean {
            requested += capability
            return grant
        }
        override suspend fun grant(requestId: String) {}
        override suspend fun deny(requestId: String) {}
        override suspend fun decide(requestId: String, granted: Boolean, scope: DecisionScope) {}
        override suspend fun revoke(capability: Capability) {}
        override suspend fun isGranted(capability: Capability): Boolean = false
        override suspend fun resetDecisions() {}
    }

    private class FakeTool(
        private val block: suspend (String) -> ToolResult,
        override val riskLevel: RiskLevel = RiskLevel.SAFE,
        override val timeoutMs: Long = 5_000
    ) : Tool {
        override val id = "fake.tool"
        override val title = "Fake tool"
        override val description = "test tool"
        override val requiredCapabilities: Set<Capability> = setOf(Capability.NETWORK)
        override val parametersSchemaJson = "{}"
        var executions = 0
        override suspend fun execute(argumentsJson: String): ToolResult {
            executions++
            return block(argumentsJson)
        }
    }

    private suspend fun build(
        permissions: PermissionManager,
        tool: FakeTool,
        maxRetries: Int = 0
    ): DefaultToolExecutor {
        val registry = InMemoryToolRegistry()
        registry.register(tool)
        return DefaultToolExecutor(
            registry = registry,
            permissions = permissions,
            logger = null,
            maxRetries = maxRetries,
            retryDelayMs = 10,
            timeoutCeilingMs = 30_000
        )
    }

    @Test
    fun `unknown tool fails without touching permissions`() = runTest {
        val registry = InMemoryToolRegistry()
        val permissions = FakePermissions()
        val executor = DefaultToolExecutor(registry, permissions)

        val result = executor.execute("missing.tool", "{}")

        assertTrue(result is ToolResult.Failure)
        assertTrue(permissions.requested.isEmpty())
    }

    @Test
    fun `denied permission prevents execution`() = runTest {
        val tool = FakeTool { ToolResult.Success("ran") }
        val executor = build(FakePermissions(grant = false), tool)

        val result = executor.execute(tool.id, "{}")

        assertTrue(result is ToolResult.Failure)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `granted tool executes and returns output`() = runTest {
        val tool = FakeTool { ToolResult.Success("42") }
        val executor = build(FakePermissions(grant = true), tool)

        val result = executor.execute(tool.id, "{}")

        assertEquals("42", (result as ToolResult.Success).output)
        assertEquals(1, tool.executions)
    }

    @Test
    fun `crashing tool retries up to maxRetries then fails`() = runTest {
        val tool = FakeTool { throw IllegalStateException("boom") }
        val executor = build(FakePermissions(grant = true), tool, maxRetries = 2)

        val result = executor.execute(tool.id, "{}")

        assertTrue(result is ToolResult.Failure)
        assertEquals(3, tool.executions)
    }

    @Test
    fun `transient crash recovers on retry`() = runTest {
        var attempts = 0
        val tool = FakeTool {
            attempts++
            if (attempts == 1) throw IllegalStateException("flaky")
            ToolResult.Success("ok")
        }
        val executor = build(FakePermissions(grant = true), tool, maxRetries = 1)

        val result = executor.execute(tool.id, "{}")

        assertEquals("ok", (result as ToolResult.Success).output)
        assertEquals(2, tool.executions)
    }

    @Test
    fun `slow tool times out with TimedOut result`() = runTest {
        val tool = FakeTool(
            block = { delay(60_000); ToolResult.Success("never") },
            timeoutMs = 100
        )
        val executor = build(FakePermissions(grant = true), tool)

        val result = executor.execute(tool.id, "{}")

        assertTrue(result is ToolResult.TimedOut)
        assertEquals(100L, (result as ToolResult.TimedOut).timeoutMs)
    }
}
