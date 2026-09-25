package com.neuron.ai.context

import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority
import com.neuron.ai.context.model.ContextSourceType
import com.neuron.ai.context.providers.ConversationContextProvider
import com.neuron.ai.context.providers.MemoryContextProvider
import com.neuron.ai.context.providers.TaskContextProvider
import com.neuron.ai.context.ranker.ContextRanker
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.memory.MemoryEntry
import com.neuron.ai.core.memory.MemoryType
import com.neuron.ai.core.task.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 context orchestration (CONTEXT_ARCHITECTURE.md §4–§5, §10):
 * providers wrap existing managers as thin adapters, and the ranker orders
 * candidates by hard priority tiers with relevance/recency as tie-breakers.
 */
class ContextRankerTest {

    // ---- Ranker ---------------------------------------------------------------

    private fun item(
        id: String,
        priority: ContextPriority,
        relevance: Float = 0f,
        ageMs: Long = 0L,
        chars: Int = 100
    ) = ContextItem(
        id = id,
        sourceType = ContextSourceType.CONVERSATION,
        sourceId = id,
        priority = priority,
        relevanceScore = relevance,
        content = "x".repeat(chars),
        timestampMs = 1_000_000L - ageMs,
        conversationId = "c1"
    )

    @Test
    fun `priority is a hard tier - P1 beats P6 even with lower relevance`() {
        val ranked = ContextRanker.rank(
            listOf(
                item("old-high", ContextPriority.OLDER_HISTORY, relevance = 0.99f),
                item("current", ContextPriority.CURRENT_REQUEST, relevance = 0.01f)
            ),
            query = "anything"
        )
        assertEquals("current", ranked.first().id)
    }

    @Test
    fun `within a tier relevance breaks ties`() {
        val ranked = ContextRanker.rank(
            listOf(
                item("weak", ContextPriority.RECENT_CHAT, relevance = 0.2f),
                item("strong", ContextPriority.RECENT_CHAT, relevance = 0.9f)
            ),
            query = "anything"
        )
        assertEquals("strong", ranked.first().id)
    }

    @Test
    fun `within a tier equal relevance - newer wins`() {
        val ranked = ContextRanker.rank(
            listOf(
                item("older", ContextPriority.RECENT_CHAT, ageMs = 5_000),
                item("newer", ContextPriority.RECENT_CHAT, ageMs = 100)
            ),
            query = "anything"
        )
        assertEquals("newer", ranked.first().id)
    }

    @Test
    fun `query keyword overlap boosts relevance within a tier`() {
        val ranked = ContextRanker.rank(
            listOf(
                item("about-terminal", ContextPriority.OLDER_HISTORY, relevance = 0.1f,
                    chars = 400).let {
                    it.copy(content = "user discussed terminal permission settings at length")
                },
                item("other", ContextPriority.OLDER_HISTORY, relevance = 0.1f).let {
                    it.copy(content = "completely unrelated grocery list content here")
                }
            ),
            query = "terminal permission"
        )
        assertEquals("about-terminal", ranked.first().id)
    }

    @Test
    fun `rank is stable - equal items keep input order`() {
        val input = listOf(
            item("a", ContextPriority.MEMORY),
            item("b", ContextPriority.MEMORY),
            item("c", ContextPriority.MEMORY)
        )
        val ranked = ContextRanker.rank(input, query = "q")
        assertEquals(listOf("a", "b", "c"), ranked.map { it.id })
    }

    // ---- Providers (thin adapters over existing managers) ----------------------

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

    @Test
    fun `conversation provider maps messages to prioritized items and drops system errors`() = runTest {
        val messages = listOf(
            message("m1", Message.Role.SYSTEM, "you are Neuron"),
            message("m2", Message.Role.USER, "help me with the build", ageMs = 3_000),
            message("m3", Message.Role.ASSISTANT, "sure, checking", ageMs = 2_000),
            message("m4", Message.Role.TOOL, "exit=1\n[err] failed", ageMs = 1_000)
                .let { it.copy(metadata = MessageMetadata(toolCallId = "call-1", toolName = "terminal.run")) },
            Message(
                id = "m5", conversationId = "c1", role = Message.Role.ASSISTANT,
                content = "boilerplate", createdAtEpochMs = 500L,
                metadata = MessageMetadata(isError = true)
            )
        )

        val items = ConversationContextProvider().fetch(
            messages = messages,
            conversationId = "c1",
            currentUserId = "m2"
        )

        assertTrue(items.none { it.id == "m5" }) // error rows are noise
        val byId = items.associateBy { it.id }
        assertEquals(ContextPriority.SYSTEM, byId.getValue("m1").priority)
        assertEquals(ContextPriority.CURRENT_REQUEST, byId.getValue("m2").priority)
        assertEquals(ContextPriority.RECENT_CHAT, byId.getValue("m3").priority)
        assertEquals(ContextPriority.TASK_STATE, byId.getValue("m4").priority) // TOOL rows
    }

    @Test
    fun `memory provider scopes to workspace and global - never other workspaces`() = runTest {
        val entries = listOf(
            entry("e1", MemoryType.USER_PREFERENCE, "global", "pref"),
            entry("e2", MemoryType.PROJECT, "ws-1", "project fact"),
            entry("e3", MemoryType.PROJECT, "ws-OTHER", "must not leak")
        )
        val provider = MemoryContextProvider { type, scopeId, limit ->
            // Simulates MemoryStore.relevant() scoping.
            entries.filter {
                (it.scopeId == scopeId || it.scopeId == "global") && it.type == type
            }.take(limit)
        }

        val items = provider.fetch(
            conversationId = "c1",
            workspaceId = "ws-1",
            query = "project pref",
            limit = 10
        )

        val ids = items.map { it.sourceId }
        assertTrue("e1" in ids)
        assertTrue("e2" in ids)
        assertTrue("e3" !in ids) // cross-workspace isolation (§10)
        assertTrue(items.all { it.priority == ContextPriority.MEMORY })
    }

    @Test
    fun `task provider surfaces only live and recent tasks for this conversation`() = runTest {
        val tasks = MutableStateFlow(
            listOf(
                task("t1", Task.Status.RUNNING, conversationId = "c1"),
                task("t2", Task.Status.DONE, conversationId = "c1"),
                task("t3", Task.Status.RUNNING, conversationId = "c-OTHER")
            )
        )
        val provider = TaskContextProvider { tasks.value }

        val items = provider.fetch(conversationId = "c1", taskId = null)

        val ids = items.map { it.sourceId }
        assertTrue("t1" in ids)             // live task for this chat
        assertTrue("t3" !in ids)            // other conversation (§10 isolation)
        // DONE tasks are summarized as outcome memory, still available:
        assertTrue("t2" in ids)
    }

    // ---- fixtures ---------------------------------------------------------------

    private fun entry(
        id: String,
        type: MemoryType,
        scopeId: String,
        key: String
    ) = MemoryEntry(
        id = id, type = type, scopeId = scopeId, key = key,
        value = "value of $key", createdAtEpochMs = 1L, updatedAtEpochMs = 1L
    )

    private fun task(
        id: String,
        status: Task.Status,
        conversationId: String?
    ) = Task(
        id = id, title = "task $id", status = status,
        conversationId = conversationId, createdAtEpochMs = 1L, updatedAtEpochMs = 1L
    )
}
