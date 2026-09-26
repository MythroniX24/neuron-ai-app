package com.neuron.ai.data.web

import com.neuron.ai.core.web.SearchQuery
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reranks aggregated hits (§ Result quality). The score is a weighted blend of
 * interpretable 0..1 components:
 *
 *  - provider relevance   (original engine ordering — position 1 is best)
 *  - corroboration        (independent providers returning the same URL)
 *  - keyword overlap      (how many query terms appear in title/snippet)
 *  - domain quality       (reference/official domains score high)
 *  - recency              (only applied when [SearchQuery.recencyDays] is set)
 *
 * Pure functions only — no I/O — so it is trivially unit-testable and the
 * weights can be tuned without touching the network layer.
 */
object SearchReranker {

    /** One reranked hit with its computed score, for logging and tests. */
    data class RankedResult(
        val result: AggregatedResult,
        val score: Double,
        val components: Components
    )

    /** Individual component scores in 0..1, kept for explainability. */
    data class Components(
        val relevance: Double,
        val corroboration: Double,
        val keywordOverlap: Double,
        val domainQuality: Double,
        val recency: Double
    )

    /** Tunable blend; weights sum to 1.0. */
    private const val W_RELEVANCE = 0.40
    private const val W_CORROB = 0.15
    private const val W_KEYWORD = 0.20
    private const val W_DOMAIN = 0.15
    private const val W_RECENCY = 0.10

    /**
     * Reranks and returns the top [limit] hits ordered by score (descending).
     * Ties are broken by bestPosition so the list stays stable.
     */
    fun rerank(
        results: List<AggregatedResult>,
        query: SearchQuery,
        limit: Int = 10
    ): List<RankedResult> {
        val terms = queryTerms(query.text)
        return results
            .map { rankOne(it, query, terms) }
            .sortedWith(compareByDescending<RankedResult> { it.score }.thenBy { it.result.bestPosition })
            .take(limit)
    }

    private fun rankOne(
        result: AggregatedResult,
        query: SearchQuery,
        terms: List<String>
    ): RankedResult {
        val relevance = positionScore(result.bestPosition)
        val corroboration = corroborationScore(result.seenInProviders.size)
        val keyword = keywordOverlapScore(result, terms)
        val domain = domainQualityScore(result.domain)
        val recency = recencyScore(query.recencyDays)

        val score = W_RELEVANCE * relevance +
            W_CORROB * corroboration +
            W_KEYWORD * keyword +
            W_DOMAIN * domain +
            W_RECENCY * recency
        return RankedResult(
            result = result,
            score = (score * 1000).roundToInt() / 1000.0,
            components = Components(relevance, corroboration, keyword, domain, recency)
        )
    }

    /** Position 1 → 1.0, decaying with rank; positions beyond 10 floor near 0. */
    internal fun positionScore(position: Int): Double =
        (1.0 / (1.0 + (position - 1).coerceAtLeast(0) * 0.25)).coerceIn(0.0, 1.0)

    /**
     * Independent corroboration: 1 provider = 0, 2 = 0.6, 3+ = 1.0.
     * Returns an empty hit (0.0) only when there are zero providers, which
     * should not happen for aggregated results.
     */
    internal fun corroborationScore(distinctProviders: Int): Double = when {
        distinctProviders <= 0 -> 0.0
        distinctProviders == 1 -> 0.0
        distinctProviders == 2 -> 0.6
        else -> 1.0
    }

    /**
     * Fraction of query terms (3+ char, minus stopwords) found in
     * title/snippet/url. 0 when the query has no usable terms.
     */
    internal fun keywordOverlapScore(result: AggregatedResult, terms: List<String>): Double {
        if (terms.isEmpty()) return 0.0
        val haystack = buildString {
            append(result.title.lowercase())
            append(' ')
            append(result.snippet?.lowercase().orEmpty())
            append(' ')
            append(result.domain?.lowercase().orEmpty())
        }
        val hits = terms.count { haystack.contains(it) }
        return hits.toDouble() / terms.size
    }

    /** Splits the query into lowercase 3+ char terms, dropping stopwords. */
    internal fun queryTerms(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()

    /**
     * Domain quality tiers. Deliberately conservative — the score nudges, it
     * does not gate: no domain is excluded, low-trust patterns just get 0.
     */
    internal fun domainQualityScore(domain: String?): Double {
        if (domain.isNullOrBlank()) return 0.5
        val d = domain.lowercase().removePrefix("www.")
        // Reference / official / primary sources.
        HIGH_TIERS.firstOrNull { d == it || d.endsWith(".$it") }?.let { return 1.0 }
        // Documentation hubs and established reference sites.
        MEDIUM_TIERS.firstOrNull { d == it || d.endsWith(".$it") }?.let { return 0.8 }
        // Low-trust / SEO-farm patterns.
        return when {
            d.startsWith("blog.") && d.count { it == '.' } >= 2 -> 0.2
            LOW_TIERS.any { d == it || d.endsWith(".$it") } -> 0.2
            else -> 0.5
        }
    }

    /**
     * Recency score when the caller asked for fresh results. Without a
     * recency window the component is neutral (0.5) so it does not distort
     * the blend. We cannot know true publish dates from snippets, so this
     * stays a coarse prior, not a claim.
     */
    internal fun recencyScore(recencyDays: Int?): Double = when (recencyDays) {
        null -> 0.5
        else -> 0.7 // ask-fresh prior; per-hit dates are unavailable at this layer
    }

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "what", "when",
        "where", "who", "how", "why", "are", "was", "were", "you", "your",
        "aur", "hai", "hain", "kya", "kaise", "kyun"
    )

    private val HIGH_TIERS = setOf(
        "wikipedia.org", "gov", "edu", "int", "who.int", "un.org", "iso.org",
        "nist.gov", "ieee.org", "acm.org", "arxiv.org", "nature.com", "science.org"
    )

    private val MEDIUM_TIERS = setOf(
        "developer.mozilla.org", "docs.oracle.com", "docs.python.org", "kotlinlang.org",
        "developer.android.com", "stackoverflow.com", "github.com", "gitlab.com",
        "reuters.com", "apnews.com", "bbc.com", "npr.org", "archive.org",
        "python.org", "rust-lang.org", "golang.org", "openjdk.org", "debian.org"
    )

    private val LOW_TIERS = listOf(
        "pinterest.com", "quora.com", "facebook.com", "medium.com", "buzzfeed.com",
        "answers.com", "ehow.com", "wikihow.com", "slideshare.net", "scribd.com"
    )
}
