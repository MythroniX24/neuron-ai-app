package com.neuron.ai.context.orchestrator

import com.neuron.ai.context.budget.TokenBudgetManager
import com.neuron.ai.context.compaction.CompactionManager
import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority
import com.neuron.ai.context.model.ContextSourceType
import com.neuron.ai.context.providers.ConversationContextProvider
import com.neuron.ai.context.providers.MemoryContextProvider
import com.neuron.ai.context.providers.TaskContextProvider
import com.neuron.ai.context.ranker.ContextRanker
import com.neuron.ai.core.provider.ChatMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Single context assembly point (CONTEXT_ARCHITECTURE.md §5).
 *
 * Flow: providers fetch candidates in PARALLEL → [ContextRanker] orders them
 * (hard priority tiers, relevance/recency tie-breaks) → [TokenBudgetManager]
 * fits them into the selected model's window → ordered [ChatMessage] list.
 *
 * Phase 3 ships this in SHADOW MODE (§14.3): ChatViewModel.buildHistory()
 * runs both this orchestrator and the legacy ChatContextEngine, logs the two
 * side by side, and still feeds the model the legacy result until verified.
 *
 * Failure handling (§12): every provider call is failure-tolerant — one broken
 * provider degrades to fewer candidates, never a broken chat. A dropped
 * repository (empty messages) or an Impossible fit (protected tiers alone
 * exceed the budget) falls back to the SAFE MINIMAL context: system + current
 * request + last N recent messages.
 */
class ContextOrchestrator(
    private val conversationProvider: ConversationContextProvider =
        ConversationContextProvider(),
    private val memoryProvider: MemoryContextProvider,
    private val taskProvider: TaskContextProvider,
    /** Pulls the raw conversation log once; shared by both assembly paths. */
    private val messagesProvider: suspend (String) -> List<com.neuron.ai.core.conversation.Message>,
    private val budgetManager: TokenBudgetManager = TokenBudgetManager(),
    /** §8: structured compaction when the ranked fit is still over budget. */
    private val compactionManager: CompactionManager = CompactionManager(),
    /** Safe-minimal fallback keeps at most this many recent turns. */
    private val minimalRecentCount: Int = 8
) {

    /** Assembled context plus the numbers the shadow log reports. */
    data class BuiltContext(
        val messages: List<ChatMessage>,
        val tokenUsage: Int,
        val tokenBudgetTotal: Int,
        val candidateCount: Int,
        val keptCount: Int,
        val droppedCount: Int,
        /** True when the safe minimal fallback was used instead of the ranked fit. */
        val usedMinimalFallback: Boolean,
        /** True when a §8 compaction pass ran over the fitted result. */
        val compacted: Boolean = false,
        /** False = compaction ran but §14.4 rejected the summary (raw eviction only). */
        val compactionSummaryIncluded: Boolean = false
    )

    suspend fun buildContext(
        query: String,
        conversationId: String,
        currentUserId: String?,
        workspaceId: String?,
        taskId: String?,
        contextWindowTokens: Int,
        /**
         * Parity with the legacy engine: the CURRENT user request travels via
         * the agent goal's instruction, not the history — when true, P1 rows
         * are excluded so the model never sees the request twice.
         */
        excludeCurrentRequest: Boolean = false
    ): BuiltContext {
        // ---- 0. One repository read, shared by both paths (§10 isolation —
        //         the conversation provider filters by conversationId). ------
        val messages = runCatching { messagesProvider(conversationId) }
            .getOrDefault(emptyList())

        // ---- 1. Providers fetch candidates in parallel (§5). ---------------
        val candidates: List<ContextItem> = try {
            coroutineScope {
                val deferred = listOf(
                    async {
                        runCatching {
                            conversationProvider.fetch(messages, conversationId, currentUserId)
                        }.getOrDefault(emptyList())
                    },
                    async {
                        runCatching {
                            memoryProvider.fetch(conversationId, workspaceId, query)
                        }.getOrDefault(emptyList())
                    },
                    async {
                        runCatching {
                            taskProvider.fetch(conversationId, taskId)
                        }.getOrDefault(emptyList())
                    }
                )
                deferred.awaitAll().flatten()
            }
                .let { fetched ->
                    if (excludeCurrentRequest) {
                        fetched.filter { it.priority != ContextPriority.CURRENT_REQUEST }
                    } else {
                        fetched
                    }
                }
        } catch (t: Throwable) {
            // Scope itself broke (shouldn't happen with per-async runCatching)
            // — degrade to the safe minimal context instead of crashing.
            return minimal(messages, currentUserId, contextWindowTokens, excludeCurrentRequest)
        }

        if (candidates.isEmpty()) {
            return minimal(messages, currentUserId, contextWindowTokens, excludeCurrentRequest)
        }

        // ---- 2. Rank, 3. Fit. ------------------------------------------------
        val ranked = ContextRanker.rank(candidates, query)
        val budget = budgetManager.computeBudget(contextWindowTokens)
        return when (val fit = budgetManager.fitToBudget(ranked, budget)) {
            is TokenBudgetManager.FitResult.Fits -> BuiltContext(
                messages = assemble(fit.items),
                tokenUsage = fit.tokenUsage,
                tokenBudgetTotal = budget.total,
                candidateCount = candidates.size,
                keptCount = fit.items.size,
                droppedCount = 0,
                usedMinimalFallback = false
            )
            is TokenBudgetManager.FitResult.OverBudget -> {
                // §5/§8: compaction runs ONLY when over budget — evict the
                // oldest droppable items, prepend their StructuredState. The
                // summary's own cost is part of the eviction math; §14.4
                // validation failure leaves the raw eviction result. A bug
                // here must never break the turn (§12) — fall back to the
                // plain fitted list.
                val compacted = runCatching {
                    compactionManager.compact(fit.items, budget.usableForInput, messages)
                }.getOrNull()

                if (compacted != null) {
                    BuiltContext(
                        messages = assemble(compacted.items),
                        tokenUsage = compacted.items.sumOf { it.tokenEstimate },
                        tokenBudgetTotal = budget.total,
                        candidateCount = candidates.size,
                        keptCount = compacted.items.size,
                        droppedCount = fit.dropped.size + compacted.evictedCount,
                        usedMinimalFallback = false,
                        compacted = true,
                        compactionSummaryIncluded = compacted.summaryIncluded
                    )
                } else {
                    BuiltContext(
                        messages = assemble(fit.items),
                        tokenUsage = fit.tokenUsage,
                        tokenBudgetTotal = budget.total,
                        candidateCount = candidates.size,
                        keptCount = fit.items.size,
                        droppedCount = fit.dropped.size,
                        usedMinimalFallback = false
                    )
                }
            }
            TokenBudgetManager.FitResult.Impossible -> {
                val fallback = minimal(messages, currentUserId, contextWindowTokens, excludeCurrentRequest)
                fallback.copy(
                    candidateCount = candidates.size,
                    droppedCount = candidates.size
                )
            }
        }
    }

    /**
     * Ordered assembly: system blocks first, then everything else oldest →
     * newest so the multi-turn wire format reads chronologically. Roles,
     * tool-call ids and attachments come straight from the items (wire
     * fidelity with the legacy engine's output).
     */
    private fun assemble(items: List<ContextItem>): List<ChatMessage> {
        val system = items.filter { it.priority == ContextPriority.SYSTEM }
            .map { it.toChatMessage() }
        val rest = items.asSequence()
            .filter { it.priority != ContextPriority.SYSTEM }
            .sortedWith(compareBy({ it.timestampMs }, { it.id }))
            .map { it.toChatMessage() }
            .toList()
        return system + rest
    }

    /** §12/§14.3 safe minimal context: system + current request + last N recent. */
    private fun minimal(
        messages: List<com.neuron.ai.core.conversation.Message>,
        currentUserId: String?,
        contextWindowTokens: Int,
        excludeCurrentRequest: Boolean
    ): BuiltContext {
        val items = conversationProvider.fetch(messages, "", currentUserId)

        val system = items.filter { it.priority == ContextPriority.SYSTEM }
        val current = items.filter {
            it.priority == ContextPriority.CURRENT_REQUEST && !excludeCurrentRequest
        }
        val recent = items
            .filter { it.priority == ContextPriority.RECENT_CHAT }
            .sortedWith(compareBy({ it.timestampMs }, { it.id }))
            .takeLast(minimalRecentCount)

        // Pack from the newest end; system + current request always survive.
        val budget = budgetManager.computeBudget(contextWindowTokens)
        val optional = recent.asReversed()
        var usage = 0
        val keptOptional = mutableListOf<ContextItem>()
        for (item in optional) {
            if (usage + item.tokenEstimate <= budget.usableForInput) {
                keptOptional += item
                usage += item.tokenEstimate
            }
        }

        val kept = system + current + keptOptional
        return BuiltContext(
            messages = assemble(kept),
            tokenUsage = usage + system.sumOf { it.tokenEstimate } +
                current.sumOf { it.tokenEstimate },
            tokenBudgetTotal = budget.total,
            candidateCount = items.size,
            keptCount = kept.size,
            droppedCount = items.size - kept.size,
            usedMinimalFallback = true
        )
    }

    private fun ContextItem.toChatMessage(): ChatMessage = ChatMessage(
        role = wireRole ?: when {
            priority == ContextPriority.SYSTEM -> ChatMessage.Role.SYSTEM
            priority == ContextPriority.CURRENT_REQUEST -> ChatMessage.Role.USER
            sourceType == ContextSourceType.TOOL_RESULT -> ChatMessage.Role.TOOL
            // Memory/workspace/task facts are guidance, not conversation turns.
            sourceType == ContextSourceType.MEMORY ||
                sourceType == ContextSourceType.WORKSPACE ||
                sourceType == ContextSourceType.TASK -> ChatMessage.Role.SYSTEM
            else -> ChatMessage.Role.ASSISTANT
        },
        content = content,
        attachments = attachments,
        toolCallId = toolCallId ?: if (sourceType == ContextSourceType.TOOL_RESULT) sourceId else null
    )

    companion object {
        /**
         * §14.3 shadow comparison: a pure, read-only report of both context
         * paths (legacy engine vs orchestrator). Logging only — it never
         * mutates either result.
         */
        fun builtFrom(
            legacyMessages: List<ChatMessage>,
            built: BuiltContext
        ): Map<String, String> = mapOf(
            "legacy" to "messages=${legacyMessages.size} " +
                "chars=${legacyMessages.sumOf { it.content.length }}",
            "orchestrator" to "messages=${built.messages.size} " +
                "chars=${built.messages.sumOf { it.content.length }} " +
                "candidates=${built.candidateCount} kept=${built.keptCount} " +
                "dropped=${built.droppedCount} " +
                "tokens=${built.tokenUsage}/${built.tokenBudgetTotal} " +
                "minimalFallback=${built.usedMinimalFallback} " +
                "compacted=${built.compacted} " +
                "summary=${built.compactionSummaryIncluded}"
        )
    }
}
