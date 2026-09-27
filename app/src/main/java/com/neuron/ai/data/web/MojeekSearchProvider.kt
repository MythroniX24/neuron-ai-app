package com.neuron.ai.data.web

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.web.SearchProvider
import com.neuron.ai.core.web.SearchQuery
import com.neuron.ai.core.web.SearchResponse
import com.neuron.ai.core.web.SearchResult
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keyless Mojeek search: scrapes www.mojeek.com/search — an independent
 * engine with its OWN crawler index (not a Bing/Google front-end), so it is
 * a genuinely independent third voice for corroboration. NO API key, NO
 * account, NO paid tier; requests come from the user's own device/network.
 * Markup changes yield zero results (failure), never a crash.
 */
class MojeekSearchProvider(
    dispatchers: DispatcherProvider,
    httpClient: OkHttpClient? = null
) : SearchProvider {

    override val id: String = "mojeek-html"

    private val io = dispatchers.io
    private val client: OkHttpClient = httpClient ?: SearchHttp.client()

    override suspend fun search(query: SearchQuery): SearchResponse = withContext(io) {
        if (query.text.isBlank()) return@withContext SearchResponse.Failure("Empty search query.")

        val url = "https://www.mojeek.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.text)
            .build()
            .toString()

        val html = fetchHtml(url)
            ?: return@withContext SearchResponse.Failure(
                "Mojeek unavailable or blocking automated queries."
            )
        val results = parse(html)
        if (results.isEmpty()) {
            SearchResponse.Failure("Mojeek returned no parseable results.")
        } else {
            SearchResponse.Success(query.text, results)
        }
    }

    private fun fetchHtml(url: String): String? = try {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else {
                val source = response.body?.source() ?: return null
                source.request(MAX_HTML_BYTES)
                val buffer = okio.Buffer()
                buffer.write(source.buffer, minOf(source.buffer.size, MAX_HTML_BYTES))
                buffer.readUtf8()
            }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Mojeek's organic hits are <a class="ob" href="URL">TITLE</a> anchors
     * followed by a <p class="s"> snippet. Anything else simply doesn't match.
     */
    internal fun parse(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = linkedSetOf<String>()
        val anchorRegex = Regex("(?is)<a[^>]+class=\"[^\"]*\\bob\\b[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")

        for (match in anchorRegex.findAll(html)) {
            if (results.size >= MAX_RESULTS) break
            val url = match.groupValues[1].trim()
            if (!url.startsWith("http")) continue
            val key = url.substringBefore('?').substringBefore('#')
            if (!seen.add(key)) continue

            val title = HtmlText.fragmentText(match.groupValues[2], 300)
            if (title.isBlank()) continue

            // Nearest snippet paragraph after the anchor.
            val tail = html.substring(match.range.last + 1, minOf(html.length, match.range.last + 2_000))            val snippetMatch = Regex("(?is)<p[^>]*class=\"[^\"]*\\bs\\b[^\"]*\"[^>]*>(.*?)</p>").find(tail)
            val snippet = snippetMatch?.let { HtmlText.fragmentText(it.groupValues[1], 400) }

            results += SearchResult(
                title = title,
                url = url,
                snippet = snippet?.ifBlank { null },
                position = results.size + 1,
                domain = url.host()
            )
        }
        return results
    }

    private fun String.host(): String? =
        runCatching { java.net.URI(this).host?.removePrefix("www.") }.getOrNull()

    companion object {
        private const val MAX_HTML_BYTES = 400L * 1024L
        private const val MAX_RESULTS = 10
    }
}
