package com.neuron.ai.context

import com.neuron.ai.context.orchestrator.ContextOrchestrator
import com.neuron.ai.context.providers.ConversationContextProvider
import com.neuron.ai.context.providers.MemoryContextProvider
import com.neuron.ai.context.providers.TaskContextProvider
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.task.Task
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-3 context orchestration (CONTEXT_ARCHITECTURE.md §5, §12, §14.3):
 * the orchestrator assembles an ordered, budget-fitted [ChatMessage] context
 * from the Phase-2 providers, degrades gracefully when providers fail, and
 * reports a side-by-side shadow comparison without changing either result.
 */
class ContextOrchestratorTest {

    // ---- fixtures ----------------------------------------------------------------

    private fun message(
        id: String,
        role: Message.Role,
        content: String,
        ageMs: Long = 0L
    ) = Message(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        createdAtEpochMs = 1_000_000L - ageMs
    )

    private fun orchestrator(
        messages: List<Message>,
        memories: List<String> = emptyList(),
        tasks: List<Task> = emptyList()
    ) = ContextOrchestrator(
        conversationProvider = ConversationContextProvider(),
        memoryProvider = MemoryContextProvider { _, _, _ -> emptyList() },
        taskProvider = TaskContextProvider { tasks },
        messagesProvider = { messages }
    )

    // ---- assembly ------------------------------------------------------------------

    @Test
    fun `assembles ordered wire messages under budget`() = runTest {
        val messages = listOf(
            message("m1", Message.Role.SYSTEM, "you are Neuron", ageMs = 9_000),
            message("m2", Message.Role.USER, "first question", ageMs = 8_000),
            message("m3", Message.Role.ASSISTANT, "first answer", ageMs = 7_000),
            message("m4", Message.Role.USER, "current request", ageMs = 0L)
        )
        val built = orchestrator(messages).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m4",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 128_000
        )

        assertFalse(built.usedMinimalFallback)
        // System first, then oldest → newest chronological order.
        assertEquals(ChatMessage.Role.SYSTEM, built.messages.first().role)
        assertEquals("you are Neuron", built.messages.first().content)
        assertEquals(
            listOf("first question", "first answer", "current request"),
            built.messages.drop(1).map { it.content }
        )
        assertEquals(ChatMessage.Role.USER, built.messages.last().role)
        assertTrue(built.tokenUsage <= built.tokenBudgetTotal)
    }

    @Test
    fun `tool rows keep their wire role and tool call id`() = runTest {
        val toolMessage = message("m3", Message.Role.TOOL, "exit=0\n✓ done", ageMs = 1_000)
            .let { it.copy(metadata = MessageMetadata(toolCallId = "call-7", toolName = "terminal")) }
        val messages = listOf(
            message("m1", Message.Role.USER, "run the tests", ageMs = 2_000),
            message("m2", Message.Role.ASSISTANT, "", ageMs = 1_500),
            toolMessage,
            message("m4", Message.Role.USER, "what happened?", ageMs = 0L)
        )

        val built = orchestrator(messages).buildContext(
            query = "what happened?",
            conversationId = "c1",
            currentUserId = "m4",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 128_000
        )

        val toolRow = built.messages.first { it.role == ChatMessage.Role.TOOL }
        assertEquals("call-7", toolRow.toolCallId)
        assertTrue(toolRow.content.contains("exit=0"))
    }

    // ---- budget / fallback -------------------------------------------------------

    @Test
    fun `over-budget old history is dropped - recent tail survives`() = runTest {
        val messages = buildList {
            add(message("m1", Message.Role.SYSTEM, "you are Neuron", ageMs = 12_000))
            repeat(40) { i ->
                add(
                    message(
                        "old-$i", Message.Role.USER, "old filler ${"x".repeat(400)}", ageMs = (10_000 - i * 100)
                    )
                )
            }
            add(message("m-now", Message.Role.USER, "current request", ageMs = 0L))
        }
        val built = orchestrator(messages).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m-now",
            workspaceId = null,
            taskId = null,
            // Small window forces the greedy fit to drop old history.
            contextWindowTokens = 4_000
        )

        assertTrue(built.droppedCount > 0)
        assertTrue(built.tokenUsage <= built.tokenBudgetTotal)
        // Newest items always survive the greedy fit.
        assertTrue(built.messages.any { it.content.contains("current request") })
    }

    @Test
    fun `impossible fit falls back to safe minimal context`() = runTest {
        // One huge system block + huge current request → protected tiers alone
        // exceed a tiny window → Impossible → minimal fallback.
        val messages = listOf(
            message("m1", Message.Role.SYSTEM, "sys ${"y".repeat(9_000)}", ageMs = 1_000),
            message("m2", Message.Role.USER, "cur ${"z".repeat(9_000)}", ageMs = 0L)
        )
        val built = orchestrator(messages).buildContext(
            query = "q",
            conversationId = "c1",
            currentUserId = "m2",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 4_000
        )

        assertTrue(built.usedMinimalFallback)
        // Minimal context keeps system + current request even when over budget.
        assertTrue(built.messages.any { it.content.startsWith("sys ") })
        assertTrue(built.messages.any { it.content.startsWith("cur ") })
    }

    @Test
    fun `excludeCurrentRequest drops the P1 row from history`() = runTest {
        val messages = listOf(
            message("m1", Message.Role.USER, "older turn", ageMs = 1_000),
            message("m2", Message.Role.USER, "current request", ageMs = 0L)
        )
        val built = orchestrator(messages).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m2",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 128_000,
            excludeCurrentRequest = true
        )

        assertTrue(built.messages.none { it.content == "current request" })
        assertTrue(built.messages.any { it.content == "older turn" })
    }

    // ---- failure tolerance (§12) ---------------------------------------------------

    @Test
    fun `empty conversation log degrades to minimal - never crashes`() = runTest {
        val built = orchestrator(emptyList()).buildContext(
            query = "hello",
            conversationId = "c1",
            currentUserId = null,
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 8_000
        )

        assertTrue(built.usedMinimalFallback)
        assertTrue(built.messages.isEmpty())
    }

    @Test
    fun `memory and task provider failures never break the build`() = runTest {
        val messages = listOf(
            message("m1", Message.Role.USER, "older turn", ageMs = 1_000),
            message("m2", Message.Role.USER, "current request", ageMs = 0L)
        )
        val built = ContextOrchestrator(
            conversationProvider = ConversationContextProvider(),
            memoryProvider = MemoryContextProvider { _, _, _ -> throw RuntimeException("mem down") },
            taskProvider = TaskContextProvider { throw RuntimeException("task down") },
            messagesProvider = { messages }
        ).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m2",
            workspaceId = "ws-1",
            taskId = "t1",
            contextWindowTokens = 128_000
        )

        // Conversation path still assembles; degraded, not broken.
        assertFalse(built.usedMinimalFallback)
        assertTrue(built.messages.isNotEmpty())
        assertTrue(built.messages.any { it.content == "older turn" })
    }

    @Test
    fun `messagesProvider failure degrades to minimal`() = runTest {
        val built = ContextOrchestrator(
            conversationProvider = ConversationContextProvider(),
            memoryProvider = MemoryContextProvider { _, _, _ -> emptyList() },
            taskProvider = TaskContextProvider { emptyList() },
            messagesProvider = { throw RuntimeException("db gone") }
        ).buildContext(
            query = "q",
            conversationId = "c1",
            currentUserId = null,
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 8_000
        )

        assertTrue(built.usedMinimalFallback)
        assertTrue(built.messages.isEmpty())
    }

    // ---- shadow report (§14.3) -------------------------------------------------------

    @Test
    fun `shadow report is read-only and describes both paths`() = runTest {
        val messages = listOf(
            message("m1", Message.Role.SYSTEM, "you are Neuron", ageMs = 1_000),
            message("m2", Message.Role.USER, "older turn", ageMs = 500),
            message("m3", Message.Role.ASSISTANT, "older answer", ageMs = 400),
            message("m4", Message.Role.USER, "current request", ageMs = 0L)
        )
        val built = orchestrator(messages).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m4",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = 128_000
        )
        val legacy = listOf(
            ChatMessage(role = ChatMessage.Role.SYSTEM, content = "you are Neuron"),
            ChatMessage(role = ChatMessage.Role.USER, content = "older turn")
        )

        val report = ContextOrchestrator.builtFrom(legacy, built)

        assertEquals(
            setOf("legacy", "orchestrator"),
            report.keys
        )
        assertTrue(report.getValue("legacy").contains("messages=2"))
        assertTrue(report.getValue("orchestrator").contains("candidates="))
        // Read-only: inputs untouched.
        assertEquals(2, legacy.size)
        assertEquals(4, built.candidateCount)
    }
}
