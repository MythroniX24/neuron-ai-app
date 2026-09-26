package com.neuron.ai.data.web

import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.web.SearchProvider
import com.neuron.ai.core.web.SearchQuery
import com.neuron.ai.core.web.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-TTL cache for identical/near-identical queries (§ Speed): web
 * results barely change within minutes, so repeat searches in one session
 * must not re-hit the network. Near-identical = same normalized term set.
 * Thread-safe; expiry is checked on read AND via periodic sweep so a long
 * session cannot accumulate stale entries.
 */
class SearchCache(private val ttlMillis: Long = DEFAULT_TTL_MILLIS) {

    data class Entry(val query: SearchQuery, val results: List<AggregatedResult>, val storedAt: Long)

    private val store = ConcurrentHashMap<String, Entry>()

    /** Puts results in the cache under both exact and normalized keys. */
    fun put(query: SearchQuery, results: List<AggregatedResult>, now: Long = System.currentTimeMillis()) {
        if (results.isEmpty()) return // never cache emptiness — retry later
        val entry = Entry(query, results, now)
        store[exactKey(query)] = entry
        store[normalizedKey(query)] = entry
        if (store.size > MAX_ENTRIES) sweep(now)
    }

    /** Returns cached results when a fresh entry exists for this query. */
    fun get(query: SearchQuery, now: Long = System.currentTimeMillis()): List<AggregatedResult>? {
        val entry = store[exactKey(query)] ?: store[normalizedKey(query)] ?: return null
        if (now - entry.storedAt > ttlMillis) {
            store.remove(exactKey(query))
            store.remove(normalizedKey(query))
            return null
        }
        return entry.results
    }

    /** Drops expired entries; returns the number removed. */
    fun sweep(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        for ((key, entry) in store) {
            if (now - entry.storedAt > ttlMillis) {
                store.remove(key)
                removed++
            }
        }
        return removed
    }

    fun size(): Int = store.size

    fun clear() = store.clear()

    private fun exactKey(query: SearchQuery): String = listOf(
        query.text.trim().lowercase(),
        query.recencyDays?.toString() ?: "-",
        query.domains.joinToString(",").lowercase(),
        query.language ?: "-",
        query.safeSearch.toString()
    ).joinToString("|")

    /** Near-identical key: normalized term order + recency window. */
    private fun normalizedKey(query: SearchQuery): String =
        SearchReranker.queryTerms(query.text).sorted().joinToString(" ") +
            "|r=" + (query.recencyDays?.toString() ?: "-")

    companion object {
        const val DEFAULT_TTL_MILLIS = 10L * 60L * 1_000L // 10 minutes
        private const val MAX_ENTRIES = 64
    }
}

/**
 * Fan-out layer with a per-provider circuit breaker (§ Reliability): a
 * provider that times out or errors repeatedly is skipped temporarily and
 * traffic flows to the remaining healthy adapters. Retries with backoff
 * apply ONLY to transient errors (IOException/timeouts) — an empty result
 * list is a valid answer and is never retried.
 *
 * Each provider gets an independent timeout; whatever finishes in time is
 * returned as partial results instead of failing the whole search.
 */
class SearchProviderRegistry(
    providers: List<SearchProvider>,
    private val logger: Logger? = null,
    /** Injectable wall clock so breaker timing is deterministic in tests. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Breaker cooldown window after tripping. */
    private val cooldownMillis: Long = COOLDOWN_MILLIS
) {

    /** Transient-only retry budget. */
    var maxRetries: Int = 1
    var retryBackoffMillis: Long = 300L

    private val adapters: List<Adapter> = providers.map { Adapter(it) }

    fun providerIds(): List<String> = adapters.map { it.provider.id }

    /** Healthy = breaker not tripped. Used to skip dead providers fast. */
    fun isHealthy(providerId: String, now: Long = this.now()): Boolean =
        adapters.firstOrNull { it.provider.id == providerId }?.let { !it.isTripped(now) } ?: false

    /**
     * Runs every healthy provider in PARALLEL under [timeoutMs], returning
     * the partial outcome per provider — Success hits, empty Success, or
     * Failure — never throwing. Unhealthy providers report a skip-marker
     * Failure so the orchestrator can log coverage honestly.
     */
    suspend fun searchFanOut(
        query: SearchQuery,
        timeoutMs: Long
    ): List<Pair<String, SearchResponse>> {
        if (adapters.isEmpty()) return emptyList()
        return coroutineScope {
            adapters.map { adapter ->
                async {
                    val id = adapter.provider.id
                    if (adapter.isTripped(now())) {
                        id to SearchResponse.Failure("circuit-open")
                    } else {
                        id to searchWithRetry(adapter, query, timeoutMs)
                    }
                }
            }.map { it.await() }
        }
    }

    private suspend fun searchWithRetry(
        adapter: Adapter,
        query: SearchQuery,
        timeoutMs: Long
    ): SearchResponse {
        var attempt = 0
        while (true) {
            val outcome = try {
                withTimeout(timeoutMs) { adapter.provider.search(query) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                adapter.recordFailure(now())
                return SearchResponse.Failure("timeout after ${timeoutMs}ms")
            } catch (t: Throwable) {
                // Never swallow structured-concurrency cancellation (tool-level
                // 30s budget) — only OUR per-provider timeouts are catchable.
                if (t is kotlinx.coroutines.CancellationException &&
                    t !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    throw t
                }
                adapter.recordFailure(now())
                if (t is IOException && attempt < maxRetries) {
                    kotlinx.coroutines.delay(retryBackoffMillis * (attempt + 1))
                    attempt++
                    continue
                }
                return SearchResponse.Failure(t.message ?: t.javaClass.simpleName)
            }
            return when (outcome) {
                is SearchResponse.Failure -> {
                    adapter.recordFailure(now())
                    outcome
                }
                is SearchResponse.Success -> {
                    adapter.recordSuccess()
                    outcome
                }
            }
        }
    }

    /** Wraps one provider with its failure counters and cooldown state. */
    private class Adapter(val provider: SearchProvider) {
        private val failures = AtomicInteger(0)
        private val cooldownUntil = AtomicLong(0)

        fun isTripped(now: Long): Boolean = now < cooldownUntil.get()

        fun recordFailure(now: Long) {
            val count = failures.incrementAndGet()
            if (count >= FAILURE_THRESHOLD) {
                cooldownUntil.set(now + cooldownMillis)
                failures.set(0)
            }
        }

        fun recordSuccess() {
            failures.set(0)
        }
    }

    companion object {
        const val FAILURE_THRESHOLD = 3
        const val COOLDOWN_MILLIS = 60L * 1_000L // 1 minute cooldown
    }
}

/**
 * The search pipeline front door (§ Architecture): cache → parallel fan-out
 * → aggregate → rerank. Replaces nothing — [SearchProvider] stays the
 * extension point; this class composes adapters behind it. Never throws;
 * degrades to whatever providers returned in time (partial results).
 */
class SearchOrchestrator(
    private val registry: SearchProviderRegistry,
    private val cache: SearchCache = SearchCache(),
    private val logger: Logger? = null,
    /** Per-provider timeout — partial results beat a slow unanimous failure. */
    private val perProviderTimeoutMs: Long = 8_000L
) {

    /**
     * Runs the pipeline. Returns merged, reranked hits plus per-provider
     * coverage for the fact-check note, or null when every provider failed
     * or the query was already cached-empty recently.
     */
    suspend fun search(query: SearchQuery): SearchOutcome {
        if (query.text.isBlank()) return SearchOutcome.Failed("Empty search query.")

        cache.get(query)?.let { cached ->
            logger?.d(TAG, "cache hit: \"${query.text}\" (${cached.size} hits)")
            return SearchOutcome.Cached(cached, corroborated = cached.count { it.seenInProviders.size >= 2 })
        }

        val fanOut = registry.searchFanOut(query, perProviderTimeoutMs)
        val providerResults = fanOut.mapNotNull { (id, response) ->
            when (response) {
                is SearchResponse.Success -> {
                    if (response.results.isNotEmpty()) id to response.results else null
                }
                is SearchResponse.Failure -> {
                    logger?.d(TAG, "provider $id failed: ${response.message}")
                    null
                }
            }
        }

        // Empty results from every healthy provider is a VALID answer —
        // never retried, never cached (§ Reliability: retry is transient-only).
        if (providerResults.isEmpty()) {
            val anyResponded = fanOut.any { it.second is SearchResponse.Success }
            return SearchOutcome.Failed(
                if (anyResponded) "No results found for \"${query.text}\"."
                else "All search providers failed or timed out — try again in a moment."
            )
        }

        val aggregated = ResultAggregator.aggregate(providerResults)
        if (aggregated.isEmpty()) {
            return SearchOutcome.Failed("No results found for \"${query.text}\".")
        }

        val ranked = SearchReranker.rerank(aggregated, query, limit = query.maxResults)
        cache.put(query, ranked.map { it.result })
        val corroborated = ranked.count { it.result.seenInProviders.size >= 2 }
        return SearchOutcome.Success(
            results = ranked.map { it.result },
            providersQueried = fanOut.map { it.first },
            providersResponded = providerResults.map { it.first },
            corroboratedCount = corroborated
        )
    }

    /** Pipeline outcome — callers pattern-match instead of catching. */
    sealed class SearchOutcome {
        /** Served from cache (already aggregated + reranked). */
        data class Cached(val results: List<AggregatedResult>, val corroborated: Int) : SearchOutcome()

        /** Fresh run with per-provider coverage for cross-check notes. */
        data class Success(
            val results: List<AggregatedResult>,
            val providersQueried: List<String>,
            val providersResponded: List<String>,
            val corroboratedCount: Int
        ) : SearchOutcome()

        data class Failed(val message: String) : SearchOutcome()
    }

    companion object {
        private const val TAG = "SearchOrchestrator"
    }
}
