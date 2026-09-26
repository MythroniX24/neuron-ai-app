package com.neuron.ai.context

import com.neuron.ai.context.compaction.CompactionManager
import com.neuron.ai.context.compaction.StructuredState
import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority
import com.neuron.ai.context.model.ContextSourceType
import com.neuron.ai.context.orchestrator.ContextOrchestrator
import com.neuron.ai.context.providers.MemoryContextProvider
import com.neuron.ai.context.providers.TaskContextProvider
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.provider.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-4 compaction (CONTEXT_ARCHITECTURE.md §8, §14.3, §14.4):
 * extractive StructuredState digests, keyword-overlap validation before a
 * summary is trusted, and orchestrator integration that only compacts when
 * the ranked fit is over budget — never breaking the turn on failure.
 */
class ContextCompactionTest {

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

    private fun item(
        id: String,
        priority: ContextPriority,
        chars: Int,
        ageMs: Long = 0L
    ) = ContextItem(
        id = id,
        sourceType = ContextSourceType.CONVERSATION,
        sourceId = id,
        priority = priority,
        content = "x".repeat(chars),
        timestampMs = 1_000_000L - ageMs,
        conversationId = "c1",
        wireRole = ChatMessage.Role.USER
    )

    /**
     * ≥5 raw turns so the digest segment (all minus the newest 4) is
     * non-empty; its keyword vocabulary is deliberately disjoint from the
     * fabricated digest used in the distortion test.
     */
    private fun summarizingSource() = listOf(
        message("m1", Message.Role.USER, "terminal permission fix request", ageMs = 5_000),
        message("m2", Message.Role.USER, "shell access was denied before", ageMs = 4_000),
        message("m3", Message.Role.ASSISTANT, "granted shell access", ageMs = 3_000),
        message("m4", Message.Role.TOOL, "exit=0", ageMs = 2_000)
            .let { it.copy(metadata = MessageMetadata(toolCallId = "c1", toolName = "terminal")) },
        message("m5", Message.Role.ASSISTANT, "verified the fix works", ageMs = 1_000),
        message("m6", Message.Role.USER, "all working now", ageMs = 0)
    )

    // ---- StructuredState -----------------------------------------------------------

    @Test
    fun `digest captures goal, decisions and tool outcomes extractively`() {
        val messages = listOf(
            message("m1", Message.Role.USER, "Fix the login timeout bug", ageMs = 8_000),
            message("m2", Message.Role.USER, "sessions expire after one minute", ageMs = 7_000),
            message("m3", Message.Role.ASSISTANT, "Found the session expiry path", ageMs = 6_000),
            message("m4", Message.Role.TOOL, "exit=0", ageMs = 5_000)
                .let { it.copy(metadata = MessageMetadata(toolCallId = "c1", toolName = "terminal")) },
            message("m5", Message.Role.USER, "great", ageMs = 4_000),
            message("m6", Message.Role.ASSISTANT, "pushing the patch", ageMs = 3_000),
            message("m7", Message.Role.USER, "is it deployed", ageMs = 2_000),
            message("m8", Message.Role.USER, "thanks", ageMs = 0)
        )

        val state = CompactionManager().buildStructuredState(messages, "c1")

        assertTrue(state.sourceCount > 0)
        assertTrue(state.goal.startsWith("Fix the login timeout bug"))
        assertTrue(state.decisions.any { it.contains("Found the session expiry path") })
        assertTrue(state.toolOutcomes.any { it.contains("exit=0") })
        // Extractive: digest lines quote the raw conversation, never invent.
        assertTrue(state.render().contains("Found the session expiry path"))
    }

    @Test
    fun `digest leaves the newest raw turns out - they stay in context raw`() {
        val messages = (1..10).map { i ->
            message("m$i", Message.Role.USER, "older turn $i", ageMs = (10 - i) * 1_000L)
        } + message("m-new-1", Message.Role.USER, "newest raw turn", ageMs = 100L) +
            message("m-new-2", Message.Role.ASSISTANT, "also raw", ageMs = 0L)

        val state = CompactionManager().buildStructuredState(messages, "c1")

        assertFalse(state.render().contains("newest raw turn"))
        assertFalse(state.render().contains("also raw"))
        // Oldest user message becomes the goal line; exactly 8 of 12 kept.
        assertTrue(state.render().contains("older turn 1"))
        assertEquals(8, state.sourceCount)
    }

    @Test
    fun `empty segment produces an empty untrusted state`() {
        val state = CompactionManager().buildStructuredState(emptyList(), "c1")
        assertEquals(0, state.sourceCount)
        assertEquals("", state.goal)
        assertFalse(CompactionManager().validate(state, emptyList()))
    }

    // ---- §14.4 validation -----------------------------------------------------------

    @Test
    fun `faithful extractive digest passes the overlap check`() {
        val manager = CompactionManager()
        val source = summarizingSource()
        val state = manager.buildStructuredState(source, "c1")
        assertTrue(manager.validate(state, source))
    }

    @Test
    fun `distorted digest fails validation - raw messages win`() {
        val manager = CompactionManager()
        // Every keyword is disjoint from the source vocabulary.
        val fabricated = StructuredState(
            goal = "unrelated quantum flux capacitor repair",
            decisions = listOf("bought bananas yesterday morning"),
            toolOutcomes = listOf("code=42 outer space"),
            sourceCount = 2
        )
        assertFalse(manager.validate(fabricated, summarizingSource()))
    }

    @Test
    fun `degenerate digest with no keywords never validates`() {
        val manager = CompactionManager()
        val empty = StructuredState(
            goal = "", decisions = emptyList(), toolOutcomes = emptyList(), sourceCount = 3
        )
        assertFalse(
            manager.validate(
                empty,
                listOf(message("m1", Message.Role.USER, "real content", ageMs = 0L))
            )
        )
    }

    // ---- compact() ------------------------------------------------------------------

    @Test
    fun `compact evicts oldest first and prepends a validated summary`() {
        val manager = CompactionManager()
        val items = listOf(
            item("old-1", ContextPriority.RECENT_CHAT, chars = 800, ageMs = 9_000),
            item("old-2", ContextPriority.RECENT_CHAT, chars = 800, ageMs = 8_000),
            item("old-3", ContextPriority.RECENT_CHAT, chars = 800, ageMs = 7_000),
            item("new", ContextPriority.RECENT_CHAT, chars = 100, ageMs = 0L)
        )
        // Usage ~625 tokens vs a 300-token usable budget forces eviction.
        val result = manager.compact(items, usableForInput = 300, sourceMessages = summarizingSource())

        assertNotNull(result)
        assertTrue(result!!.evictedCount >= 2)
        assertTrue(result.summaryIncluded)
        assertEquals(CompactionManager.SUMMARY_ID, result.items.first().id)
        assertTrue(result.items.none { it.id == "old-1" })
        assertTrue(result.items.any { it.id == "new" })
        assertTrue(result.items.sumOf { it.tokenEstimate } <= 300)
    }

    @Test
    fun `summary cost is reserved before eviction`() {
        val manager = CompactionManager()
        val items = listOf(
            item("old-1", ContextPriority.RECENT_CHAT, chars = 1_200, ageMs = 9_000),
            item("old-2", ContextPriority.RECENT_CHAT, chars = 1_200, ageMs = 8_000),
            item("new", ContextPriority.RECENT_CHAT, chars = 100, ageMs = 0L)
        )
        // 625 tokens used vs 600 usable — with the summary's own cost
        // reserved first, the fit only closes by evicting an old item.
        val result = manager.compact(items, usableForInput = 600, sourceMessages = summarizingSource())

        assertNotNull(result)
        assertTrue(result!!.summaryIncluded)
        assertTrue(result.summaryTokens > 0)
        assertTrue(result.evictedCount >= 1)
        assertTrue(result.items.sumOf { it.tokenEstimate } <= 600)
        assertTrue(result.items.any { it.id == "new" })
    }

    @Test
    fun `untrusted summary falls back to raw eviction only`() {
        val manager = object : CompactionManager() {
            override fun validate(state: StructuredState, sourceMessages: List<Message>): Boolean = false
        }
        val items = listOf(
            item("old-1", ContextPriority.RECENT_CHAT, chars = 800, ageMs = 9_000),
            item("old-2", ContextPriority.RECENT_CHAT, chars = 800, ageMs = 8_000),
            item("new", ContextPriority.RECENT_CHAT, chars = 100, ageMs = 0L)
        )
        val result = manager.compact(items, usableForInput = 300, sourceMessages = summarizingSource())

        assertNotNull(result)
        assertFalse(result!!.summaryIncluded)
        assertEquals(0, result.summaryTokens)
        assertTrue(result.items.none { it.id == "old-1" })
        assertTrue(result.items.any { it.id == "new" })
        assertTrue(result.items.sumOf { it.tokenEstimate } <= 300)
    }

    @Test
    fun `protected tiers are never evicted`() {
        val manager = object : CompactionManager() {
            override fun validate(state: StructuredState, sourceMessages: List<Message>): Boolean = false
        }
        val items = listOf(
            item("sys", ContextPriority.SYSTEM, chars = 200, ageMs = 9_000),
            item("current", ContextPriority.CURRENT_REQUEST, chars = 200, ageMs = 0L),
            item("old-1", ContextPriority.RECENT_CHAT, chars = 400, ageMs = 5_000)
        )
        // 200 tokens used vs 160 usable: only the droppable P3 item can be
        // evicted to close the gap — protected tiers must survive untouched.
        val result = manager.compact(items, usableForInput = 160, sourceMessages = summarizingSource())

        assertNotNull(result)
        assertTrue(result!!.items.any { it.id == "sys" })
        assertTrue(result.items.any { it.id == "current" })
        assertTrue(result.items.none { it.id == "old-1" })
        assertTrue(result.items.sumOf { it.tokenEstimate } <= 160)
    }

    @Test
    fun `no-op when already within budget and nothing to summarize`() {
        val manager = CompactionManager()
        val items = listOf(item("a", ContextPriority.RECENT_CHAT, chars = 100, ageMs = 0L))
        assertNull(manager.compact(items, usableForInput = 10_000, sourceMessages = emptyList()))
    }

    // ---- orchestrator integration ----------------------------------------------------

    private fun longConversation() = buildList {
        add(message("m1", Message.Role.SYSTEM, "you are Neuron", ageMs = 12_000))
        repeat(40) { i ->
            add(
                message(
                    "old-$i", Message.Role.USER,
                    "old filler about terminal permissions ${"x".repeat(400)}",
                    ageMs = (10_000L - i * 100L)
                )
            )
        }
        add(message("m-now", Message.Role.USER, "current request", ageMs = 0L))
    }

    @Test
    fun `orchestrator compacts the over-budget path with a validated summary`() = runTest {
        val messages = longConversation()
        val window = 4_000
        val built = ContextOrchestrator(
            memoryProvider = MemoryContextProvider { _, _, _ -> emptyList() },
            taskProvider = TaskContextProvider { emptyList() },
            messagesProvider = { messages }
        ).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m-now",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = window,
            excludeCurrentRequest = true
        )

        val usable = com.neuron.ai.context.budget.TokenBudgetManager()
            .computeBudget(window).usableForInput
        assertTrue(built.compacted)
        assertTrue(built.compactionSummaryIncluded)
        assertTrue(built.tokenUsage <= usable)
        assertTrue(built.messages.any { it.content.contains("compacted summary") })
        // The current request never re-enters via history (goal parity).
        assertTrue(built.messages.none { it.content.contains("current request") })
    }

    @Test
    fun `orchestrator compaction failure degrades to the plain fitted list`() = runTest {
        val messages = longConversation()
        val window = 4_000
        val built = ContextOrchestrator(
            memoryProvider = MemoryContextProvider { _, _, _ -> emptyList() },
            taskProvider = TaskContextProvider { emptyList() },
            messagesProvider = { messages },
            compactionManager = object : CompactionManager() {
                override fun compact(
                    items: List<ContextItem>,
                    usableForInput: Int,
                    sourceMessages: List<Message>
                ): CompactionManager.CompactionResult? = throw RuntimeException("boom")
            }
        ).buildContext(
            query = "current request",
            conversationId = "c1",
            currentUserId = "m-now",
            workspaceId = null,
            taskId = null,
            contextWindowTokens = window,
            excludeCurrentRequest = true
        )

        // Never breaks the turn: falls back to the plain greedy-fit result.
        assertFalse(built.compacted)
        assertTrue(built.messages.isNotEmpty())
        assertTrue(built.messages.any { it.content.startsWith("old filler") })
    }
}
