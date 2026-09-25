package com.neuron.ai.context

import com.neuron.ai.context.budget.TokenBudgetManager
import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority
import com.neuron.ai.context.model.ContextSourceType
import com.neuron.ai.context.compression.ToolResultProcessor
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 context orchestration (CONTEXT_ARCHITECTURE.md §6, §9, §14.3):
 * budget fitting invariants + tool-result compression.
 */
class ContextBudgetTest {

    private fun item(
        id: String,
        priority: ContextPriority,
        chars: Int
    ) = ContextItem(
        id = id,
        sourceType = ContextSourceType.CONVERSATION,
        sourceId = id,
        priority = priority,
        content = "x".repeat(chars),
        conversationId = "c1"
    )

    // ---- TokenBudgetManager -------------------------------------------------

    @Test
    fun `budget derives from model window minus reserved output and margin`() {
        val manager = TokenBudgetManager()
        val budget = manager.computeBudget(128_000)
        // 128k - 2k output, then ~9% off for the 10% safety margin.
        assertTrue(budget.usableForInput in 112_000..116_000)
    }

    @Test
    fun `small window floors output reservation`() {
        val manager = TokenBudgetManager()
        val budget = manager.computeBudget(4_000)
        assertTrue(budget.reservedOutput <= 1_000)
        assertTrue(budget.usableForInput > 0)
    }

    @Test
    fun `fit keeps protected tiers and drops low priority first`() {
        val manager = TokenBudgetManager()
        val budget = manager.computeBudget(8_000)
        val items = listOf(
            item("sys", ContextPriority.SYSTEM, 2_000),
            item("req", ContextPriority.CURRENT_REQUEST, 2_000),
            item("recent", ContextPriority.RECENT_CHAT, 4_000),
            item("old", ContextPriority.OLDER_HISTORY, 30_000)
        )
        val result = manager.fitToBudget(items, budget)
        assertTrue(result is TokenBudgetManager.FitResult.OverBudget)
        result as TokenBudgetManager.FitResult.OverBudget
        assertTrue(result.items.any { it.id == "sys" })
        assertTrue(result.items.any { it.id == "req" })
        assertTrue(result.dropped.any { it.id == "old" })
    }

    @Test
    fun `protected items survive even when they alone bust the budget`() {
        val manager = TokenBudgetManager()
        val budget = manager.computeBudget(4_000)
        val items = listOf(
            item("sys", ContextPriority.SYSTEM, 20_000),
            item("old", ContextPriority.OLDER_HISTORY, 5_000)
        )
        // P0 alone exceeds usable: caller falls back to minimal context.
        assertTrue(manager.fitToBudget(items, budget) is TokenBudgetManager.FitResult.Impossible)
    }

    @Test
    fun `fit never exceeds usable budget when droppable items exist`() {
        val manager = TokenBudgetManager()
        val budget = manager.computeBudget(8_000)
        val items = (1..40).map { item("m$it", ContextPriority.RECENT_CHAT, 900) }
        val result = manager.fitToBudget(items, budget)
        when (result) {
            is TokenBudgetManager.FitResult.Fits ->
                assertTrue(result.tokenUsage <= budget.usableForInput)
            is TokenBudgetManager.FitResult.OverBudget ->
                assertTrue(result.tokenUsage <= budget.usableForInput)
            else -> {}
        }
    }

    // ---- ToolResultProcessor --------------------------------------------------

    private fun toolMessage(content: String) = Message(
        id = "m1",
        conversationId = "c1",
        role = Message.Role.TOOL,
        content = content,
        createdAtEpochMs = 0L,
        metadata = MessageMetadata(toolCallId = "call-1", toolName = "terminal.run")
    )

    @Test
    fun `small tool results pass through untouched`() {
        val message = toolMessage("exit=0\nok")
        assertEquals("exit=0\nok", ToolResultProcessor.process(message))
    }

    @Test
    fun `large tool result is compressed with key lines and hint`() {
        val big = buildString {
            appendLine("Ran build for project")
            repeat(400) { appendLine("at com.example.stackframe$it(Stack.kt:$it)") }
            appendLine("[err] Compilation error in Main.kt:42")
            appendLine("exit=1")
        }
        val processed = ToolResultProcessor.process(toolMessage(big))
        assertTrue(processed.length < ProcessedToolResult.COMPRESS_THRESHOLD_CHARS + 300)
        assertTrue(processed.contains("[err] Compilation error"))
        assertTrue(processed.contains("exit=1"))
        assertTrue(processed.contains("full output"))
    }

    @Test
    fun `non-tool roles are never processed`() {
        val message = Message(
            id = "m2", conversationId = "c1", role = Message.Role.USER,
            content = "x".repeat(5_000), createdAtEpochMs = 0L
        )
        assertEquals(message.content, ToolResultProcessor.process(message))
    }
}
