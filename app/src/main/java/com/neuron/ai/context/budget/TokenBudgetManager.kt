package com.neuron.ai.context.budget

import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority

/**
 * Per-request token budget derived from the SELECTED model's context window —
 * never a global constant (CONTEXT_ARCHITECTURE.md §6).
 */
data class TokenBudget(
    val total: Int,
    val reservedOutput: Int,
    /** Explicit safety margin so an estimation miss degrades, not overflows. */
    val safetyMarginPercent: Int = 10
) {
    val usableForInput: Int get() = ((total - reservedOutput) * 100) / (100 + safetyMarginPercent)
}

/**
 * Fits ranked context items into the model-aware budget.
 *
 * Invariants (checked, and loudly logged on violation):
 *  - P0–P2 items are NEVER dropped.
 *  - The fitted result never exceeds the usable budget; on violation the
 *    caller falls back to a safe minimal context instead of sending it.
 */
class TokenBudgetManager(
    private val safetyMarginPercent: Int = 10,
    /** Reserved output tokens — conservative default for chat answers. */
    private val reservedOutputTokens: Int = 2_048
) {

    fun computeBudget(contextWindowTokens: Int): TokenBudget {
        val window = contextWindowTokens.coerceAtLeast(4_000)
        val output = reservedOutputTokens.coerceAtMost(window / 4)
        return TokenBudget(
            total = window,
            reservedOutput = output,
            safetyMarginPercent = safetyMarginPercent
        )
    }

    sealed class FitResult {
        data class Fits(val items: List<ContextItem>, val tokenUsage: Int) : FitResult()
        data class OverBudget(
            val items: List<ContextItem>,
            val tokenUsage: Int,
            val dropped: List<ContextItem>
        ) : FitResult()
        /** P0–P2 alone exceed the budget — caller must use safe minimal context. */
        data object Impossible : FitResult()
    }

    /**
     * Greedily fills by priority tier (lower level first, relevance/recency
     * ordering is the ranker's job and is assumed done upstream).
     */
    fun fitToBudget(items: List<ContextItem>, budget: TokenBudget): FitResult {
        var usage = 0
        val kept = mutableListOf<ContextItem>()
        val dropped = mutableListOf<ContextItem>()
        var protectedUsed = 0

        for (item in items.sortedBy { it.priority.level }) {
            val cost = item.tokenEstimate
            if (usage + cost <= budget.usableForInput) {
                kept += item
                usage += cost
                if (item.priority.level <= ContextPriority.TASK_STATE.level) protectedUsed += cost
            } else if (item.priority.level <= ContextPriority.TASK_STATE.level) {
                // P0–P2 are inviolable: even over budget they stay.
                kept += item
                usage += cost
                protectedUsed += cost
            } else {
                dropped += item
            }
        }

        // Invariant: protected tiers must fit — otherwise the whole fit is
        // impossible and the caller falls back to minimal context.
        if (protectedUsed > budget.usableForInput) return FitResult.Impossible
        // Invariant: never exceed budget unless protected tiers forced it.
        if (usage > budget.usableForInput && dropped.isEmpty() && kept.any {
                it.priority.level > ContextPriority.TASK_STATE.level
            }
        ) {
            return FitResult.Impossible
        }

        return if (dropped.isEmpty()) {
            FitResult.Fits(kept, usage)
        } else {
            FitResult.OverBudget(kept, usage, dropped)
        }
    }
}
