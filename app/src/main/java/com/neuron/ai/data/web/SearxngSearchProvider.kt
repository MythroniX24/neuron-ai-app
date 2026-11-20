package com.neuron.ai.data.web

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.web.SearchProvider
import com.neuron.ai.core.web.SearchQuery
import com.neuron.ai.core.web.SearchResponse
import com.neuron.ai.core.web.SearchResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder

/**
 * Keyless SearXNG search provider (the engine at the heart of Perplexica).
 * Queries public SearXNG JSON endpoints without needing any API key, with
 * automatic failover across community instances.
 */
class SearxngSearchProvider(
    dispatchers: DispatcherProvider,
    httpClient: OkHttpClient? = null,
    private val instances: List<String> = DEFAULT_INSTANCES
) : SearchProvider {

    override val id: String = "searxng-json"

    private val io = dispatchers.io
    private val client: OkHttpClient = httpClient ?: SearchHttp.client()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): SearchResponse = withContext(io) {
        if (query.text.isBlank()) return@withContext SearchResponse.Failure("Empty search query.")

        val encodedQuery = URLEncoder.encode(query.text, "UTF-8")

        for (instance in instances) {
            val results = requestInstance(instance, encodedQuery, query.maxResults)
            if (results.isNotEmpty()) {
                return@withContext SearchResponse.Success(query.text, results)
            }
        }

        SearchResponse.Failure("SearXNG search yielded no results across instances.")
    }

    private fun requestInstance(instanceUrl: String, encodedQuery: String, maxResults: Int): List<SearchResult> = try {
        val base = instanceUrl.removeSuffix("/")
        val url = "$base/search?q=$encodedQuery&format=json"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val bodyString = response.body?.string() ?: return emptyList()
            parseJson(bodyString, maxResults)
        }
    } catch (_: Throwable) {
        emptyList()
    }

    internal fun parseJson(body: String, maxResults: Int): List<SearchResult> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val resultsArray = root["results"]?.jsonArray ?: return emptyList()

        val list = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        for (item in resultsArray) {
            if (list.size >= maxResults.coerceIn(1, 20)) break
            val obj = item.jsonObject
            val url = obj["url"]?.jsonPrimitive?.content ?: continue
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue

            val cleanUrl = url.substringBefore('#')
            if (!seenUrls.add(cleanUrl)) continue

            val title = obj["title"]?.jsonPrimitive?.content?.trim() ?: "Untitled"
            val snippet = obj["content"]?.jsonPrimitive?.content?.trim()
            val domain = runCatching { URI(cleanUrl).host?.removePrefix("www.") }.getOrNull()

            list.add(
                SearchResult(
                    title = title,
                    url = cleanUrl,
                    snippet = snippet,
                    position = list.size + 1,
                    domain = domain
                )
            )
        }
        list
    }.getOrDefault(emptyList())

    companion object {
        val DEFAULT_INSTANCES = listOf(
            "https://searx.be",
            "https://search.ononoki.org",
            "https://searx.tiekoetter.com",
            "https://priv.au",
            "https://northboot.xyz"
        )
    }
}
