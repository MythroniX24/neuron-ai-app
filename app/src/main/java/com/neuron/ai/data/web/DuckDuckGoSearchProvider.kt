package com.neuron.ai.data.web

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.web.SearchProvider
import com.neuron.ai.core.web.SearchQuery
import com.neuron.ai.core.web.SearchResponse
import com.neuron.ai.core.web.SearchResult
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Real web search over DuckDuckGo's public HTML endpoint — no paid API, no
 * self-hosted backend: queries go out from the user's own device/network.
 * Parses the server-rendered result list defensively; a markup change yields
 * zero results (failure), never a crash. A lite fallback endpoint is tried
 * once if the primary layout yields nothing.
 */
class DuckDuckGoSearchProvider(
    dispatchers: DispatcherProvider,
    httpClient: OkHttpClient? = null
) : SearchProvider {

    override val id: String = "duckduckgo-html"

    private val io = dispatchers.io
    private val client: OkHttpClient = (httpClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .header("User-Agent", HttpPageFetcher.USER_AGENT)
        .build()

    override suspend fun search(query: SearchQuery): SearchResponse = withContext(io) {
        if (query.text.isBlank()) return@withContext SearchResponse.Failure("Empty search query.")

        val primary = requestHtml(query, lite = false)
        val results = primary?.let { parse(it) }.orEmpty()
        if (results.isNotEmpty()) return@withContext SearchResponse.Success(query.text, results)

        val lite = requestHtml(query, lite = true)
        val liteResults = lite?.let { parse(it) }.orEmpty()
        if (liteResults.isNotEmpty()) return@withContext SearchResponse.Success(query.text, liteResults)

        SearchResponse.Failure(
            "No results — the search engine may be blocking automated queries. Try again later."
        )
    }

    private fun requestHtml(query: SearchQuery, lite: Boolean): String? = try {
        val form = FormBody.Builder().apply {
            add("q", query.text)
            if (query.safeSearch) add("kp", "-2") else add("kp", "1")
            if (query.recencyDays != null) add("df", "d${query.recencyDays.coerceAtMost(365)}")
            if (!query.language.isNullOrBlank()) add("kl", query.language)
        }.build()

        val url = if (lite) "https://lite.duckduckgo.com/lite/" else "https://html.duckduckgo.com/html/"
        val request = Request.Builder()
            .url(url)
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else {
                // Bounded read — cap the page we buffer.
                val source = response.body?.source()
                if (source == null) null
                else {
                    source.request(MAX_HTML_BYTES)
                    val buffer = okio.Buffer()
                    buffer.write(source.buffer, minOf(source.buffer.size, MAX_HTML_BYTES))
                    buffer.readUtf8()
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Extracts anchors with class "result__a" (title + href). DDG wraps URLs
     * in /l/?uddg=<encoded> redirects — unwrap them. Deduplicates by URL.
     */
    internal fun parse(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = linkedSetOf<String>()
        val anchorRegex = Regex(
            "(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"
        )
        for (match in anchorRegex.findAll(html)) {
            if (results.size >= MAX_RESULTS) break
            val rawHref = HtmlText.decode(match.groupValues[1]).trim()
            val title = HtmlText.extractText(match.groupValues[2], 300)
            val url = unwrap(rawHref) ?: continue
            if (!url.startsWith("http")) continue
            if (!seen.add(url)) continue

            // The snippet anchor (result__snippet) nearest after this title.
            val tail = html.substring(match.range.last + 1, minOf(html.length, match.range.last + 2_000))
            val snippetMatch = Regex("(?is)<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>")
                .find(tail)
            val snippet = snippetMatch?.let { HtmlText.extractText(it.groupValues[1], 400) }

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

    /** Unwraps DDG's /l/?uddg= redirect links; returns null for non-web schemes. */
    internal fun unwrap(href: String): String? {
        val direct = Regex("(?i)^https?://").containsMatchIn(href)
        if (direct) return href
        val uddg = Regex("(?i)[?&]uddg=([^&]+)").find(href) ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(uddg.groupValues[1], "UTF-8") }
            .getOrNull() ?: return null
        return decoded.takeIf { it.startsWith("http") }
    }

    private fun String.host(): String? =
        runCatching { java.net.URI(this).host?.removePrefix("www.") }.getOrNull()

    companion object {
        private const val MAX_HTML_BYTES = 400L * 1024L
        private const val MAX_RESULTS = 12
    }
}
