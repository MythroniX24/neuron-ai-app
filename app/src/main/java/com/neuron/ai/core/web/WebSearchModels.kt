package com.neuron.ai.core.web

/**
 * Web research models (Milestone 3): a provider-agnostic search abstraction.
 * The agent's WebSearchTool depends on [SearchProvider]; adapters implement
 * real engines. No paid API is required — the default adapter works over the
 * user's own device/network.
 */

/** A user/agent search request with sensible limits. */
data class SearchQuery(
    val text: String,
    val maxResults: Int = 5,
    /** Preferred result recency window in days, when the engine supports it. */
    val recencyDays: Int? = null,
    /** Restrict results to these domains when non-empty. */
    val domains: List<String> = emptyList(),
    val language: String? = null,
    /** Engine-level safe search, honored where supported. */
    val safeSearch: Boolean = false
)

/** One organic search result. No metadata is invented — fields stay null. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String?,
    /** Result position in the returned ordering (1-based). */
    val position: Int,
    val domain: String? = null
)

/** Outcome of a search: results or a failure value (never throws). */
sealed class SearchResponse {
    data class Success(val query: String, val results: List<SearchResult>) : SearchResponse()
    data class Failure(val message: String) : SearchResponse()
}

/**
 * Pluggable search backend. The agent core never depends on a concrete
 * engine — swap adapters without touching AgentRuntime or the tool.
 */
interface SearchProvider {
    val id: String
    suspend fun search(query: SearchQuery): SearchResponse
}

/** Extracted readable text of one fetched page. */
data class PageContent(
    val url: String,
    val title: String?,
    val text: String,
    val truncated: Boolean
)

/**
 * Fetches a URL and extracts readable text. Implemented by the data layer
 * (OkHttp + regex-free HTML reduction); unit tests fake it.
 */
interface PageFetcher {
    /** Returns null on blocked/invalid/unavailable pages — never throws. */
    suspend fun fetch(url: String, maxChars: Int = 8_000): PageContent?
}
