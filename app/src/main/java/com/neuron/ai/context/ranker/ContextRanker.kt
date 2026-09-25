package com.neuron.ai.context.ranker

import com.neuron.ai.context.model.ContextItem

/**
 * Orders context candidates (CONTEXT_ARCHITECTURE.md §5):
 *
 *  score = f(priority tier, relevanceScore, recency)
 *
 * Priority is a HARD tier — a SYSTEM item always outranks a hugely relevant
 * LOW_VALUE one. Within a tier, keyword overlap with the user's query lifts
 * relevance, and newer items win final ties. Stable sort: equal candidates
 * keep their input order (deterministic tests, deterministic prompts).
 */
object ContextRanker {

    /** Newer-than-this counts as "fresh" for the recency tie-breaker. */
    private const val RECENCY_WINDOW_MS = 60_000L

    fun rank(items: List<ContextItem>, query: String, nowMs: Long = System.currentTimeMillis()): List<ContextItem> {
        val queryTerms = tokenize(query)
        return items
            .map { it to score(it, queryTerms, nowMs) }
            .sortedWith(
                compareBy(
                    { it.first.priority.level },   // hard tier
                    { -it.second }                 // relevance/recency composite
                )
            )
            .map { it.first }
    }

    /**
     * Composite within-tier score. Pure function of the item + query — cheap
     * to test exhaustively.
     */
    internal fun score(item: ContextItem, queryTerms: Set<String>, nowMs: Long): Double {
        var score = item.relevanceScore.toDouble()

        // Keyword overlap lifts relevance within the tier.
        if (queryTerms.isNotEmpty()) {
            val haystack = tokenize(item.content)
            if (haystack.isNotEmpty()) {
                val overlap = queryTerms.count { it in haystack }.toDouble() / queryTerms.size
                score = (score + overlap) / 2.0
            }
        }

        // Recency decay inside the tier (old items fade, never invert tiers).
        val age = (nowMs - item.timestampMs).coerceAtLeast(0)
        if (item.timestampMs > 0 && age > RECENCY_WINDOW_MS) {
            val decay = 1.0 / (1.0 + age / 3_600_000.0) // hours
            score *= 0.7 + 0.3 * decay
        }
        return score
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()
}
