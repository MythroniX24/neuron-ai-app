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
 * Keyless Bing web search: scrapes the server-rendered www.bing.com/search
 * HTML page — NO API key, NO account, NO paid tier. Requests go out from the
 * user's own device/network. A markup change yields zero results (failure),
 * never a crash; the registry falls back to the remaining engines.
 */
class BingHtmlSearchProvider(
    dispatchers: DispatcherProvider,
    httpClient: OkHttpClient? = null
) : SearchProvider {

    override val id: String = "bing-html"

    private val io = dispatchers.io
    private val client: OkHttpClient = httpClient ?: SearchHttp.client()

    override suspend fun search(query: SearchQuery): SearchResponse = withContext(io) {
        if (query.text.isBlank()) return@withContext SearchResponse.Failure("Empty search query.")

        val urlBuilder = "https://www.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.text)
            .addQueryParameter("count", MAX_RESULTS.coerceAtMost(30).toString())
        if (query.language.isNullOrBlank().not()) urlBuilder.addQueryParameter("setlang", query.language)
        if (query.safeSearch) urlBuilder.addQueryParameter("adlt", "strict")

        val html = fetchHtml(urlBuilder.build().toString())
            ?: return@withContext SearchResponse.Failure(
                "Bing unavailable or blocking automated queries."
            )
        val results = parse(html)
        if (results.isEmpty()) {
            SearchResponse.Failure("Bing returned no parseable results.")
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
     * Extracts Bing's organic hits: <li class="b_algo"> blocks each contain an
     * <h2><a href="...">title</a></h2> and a <p> snippet. Non-result markup
     * (ads with b_ad, news carousels) simply does not match.
     */
    internal fun parse(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = linkedSetOf<String>()
        val blockRegex = Regex("(?is)<li[^>]+class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>")
        for (block in blockRegex.findAll(html)) {
            if (results.size >= MAX_RESULTS) break
            val body = block.groupValues[1]

            val anchor = Regex("(?is)<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(body) ?: continue
            val url = unwrap(anchor.groupValues[1]) ?: continue
            val key = url.substringBefore('?').substringBefore('#')
            if (!seen.add(key)) continue

            val title = HtmlText.fragmentText(anchor.groupValues[2], 300)
            if (title.isBlank()) continue

            val snippetMatch = Regex("(?is)<p[^>]*>(.*?)</p>").find(body)
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

    /** Unwraps Bing's bing.com/ck/a tracking redirects; passes direct URLs through. */
    internal fun unwrap(href: String): String? {
        val direct = Regex("(?i)^https?://").containsMatchIn(href)
        if (direct && !href.contains("bing.com/ck/")) return href
        val u = Regex("(?i)[?&]u=a1([^&]+)").find(href) ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(u.groupValues[1], "UTF-8") }
            .getOrNull() ?: return null
        // Modern Bing wraps the target in base64; strip the leading marker
        // "a1<base64url>" → decode best-effort, else drop.
        return decodeBingTarget(decoded) ?: (decoded.takeIf { it.startsWith("http") })
    }

    private fun decodeBingTarget(encoded: String): String? {
        val body = encoded.removePrefix("a1")
        if (body.length < 8) return null
        val bytes = base64UrlDecode(body) ?: return null
        val target = String(bytes, Charsets.UTF_8)
        return target.takeIf { Regex("(?i)^https?://").containsMatchIn(it) }
    }

    /**
     * Hand-rolled base64url decoder — avoids java.util.Base64 (API 26+) and
     * android.util.Base64 (unavailable in plain JVM unit tests).
     */
    private fun base64UrlDecode(input: String): ByteArray? = runCatching {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bits = mutableListOf<Boolean>()
        for (ch in input) {
            val value = alphabet.indexOf(ch)
            if (value < 0) continue // padding/ignored chars
            for (shift in 5 downTo 0) bits.add((value shr shift) and 1 == 1)
        }
        val bytes = ByteArray(bits.size / 8)
        for (i in bytes.indices) {
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            bytes[i] = b.toByte()
        }
        bytes
    }.getOrNull()

    private fun String.host(): String? =
        runCatching { java.net.URI(this).host?.removePrefix("www.") }.getOrNull()

    companion object {
        private const val MAX_HTML_BYTES = 500L * 1024L
        private const val MAX_RESULTS = 12
    }
}
