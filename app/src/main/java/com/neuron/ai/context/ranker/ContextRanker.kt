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
     *
     * Recency is an ADDITIVE term (tiny), not just a multiplier: two items
     * with no relevance signal (both 0f) still order newer-first instead of
     * falling back to input order.
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

        // Additive recency: normalizes age against a 1h half-life, scaled
        // small enough to never outrank a genuinely more relevant item.
        if (item.timestampMs > 0) {
            val age = (nowMs - item.timestampMs).coerceAtLeast(0)
            score += 0.01 / (1.0 + age / 1_800_000.0)
        }
        return score
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()
}
