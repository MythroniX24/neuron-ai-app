package com.neuron.ai.data.web

import com.neuron.ai.core.web.SearchResult

/**
 * One merged, deduplicated hit — [seenInProviders] counts how many DISTINCT
 * providers returned it, which the reranker turns into a corroboration boost
 * and the fact-check note uses for "independent sources" counting.
 */
data class AggregatedResult(
    val title: String,
    val url: String,
    val snippet: String?,
    val domain: String?,
    val bestPosition: Int,
    /** Distinct provider ids that returned this URL. */
    val seenInProviders: List<String>
)

/**
 * Merges provider result lists (§ Result quality): deduplicates by
 * NORMALIZED url (scheme/www/query/fragment stripped — tracking params must
 * not split one hit into two), keeps the best title/snippet seen, and
 * records provider corroboration.
 */
object ResultAggregator {

    fun aggregate(
        providerResults: List<Pair<String, List<SearchResult>>>
    ): List<AggregatedResult> {
        val byUrl = LinkedHashMap<String, AggregatedResult>()
        for ((providerId, results) in providerResults) {
            for (result in results) {
                val key = normalizeUrl(result.url) ?: continue
                val existing = byUrl[key]
                if (existing == null) {
                    byUrl[key] = AggregatedResult(
                        title = result.title,
                        url = result.url,
                        snippet = result.snippet,
                        domain = result.domain ?: hostOf(result.url),
                        bestPosition = result.position,
                        seenInProviders = listOf(providerId)
                    )
                } else {
                    byUrl[key] = existing.copy(
                        title = existing.title.ifBlank { result.title },
                        snippet = existing.snippet ?: result.snippet,
                        bestPosition = minOf(existing.bestPosition, result.position),
                        seenInProviders = (existing.seenInProviders + providerId).distinct()
                    )
                }
            }
        }
        return byUrl.values.toList()
    }

    /**
     * Normalization key: lowercase, drop scheme/www., query and fragment.
     * Returns null for non-http URLs so they never enter results.
     */
    fun normalizeUrl(url: String): String? {
        if (!Regex("(?i)^https?://").containsMatchIn(url)) return null
        val noFragment = url.substringBefore('#')
        val path = noFragment.substringBefore('?')
        return path.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
}
