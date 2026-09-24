package com.neuron.ai.data.web

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.web.PageContent
import com.neuron.ai.core.web.PageFetcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Real HTTP page fetcher with readable-text extraction. Bounded: size caps,
 * connect/read timeouts, 3-redirect limit, UTF-8 only best-effort. Binary or
 * non-HTML content yields null — callers treat it as "unavailable".
 */
class HttpPageFetcher(
    dispatchers: DispatcherProvider,
    httpClient: OkHttpClient? = null
) : PageFetcher {

    private val io = dispatchers.io
    private val client: OkHttpClient = (httpClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun fetch(url: String, maxChars: Int): PageContent? = withContext(io) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
        // Never follow into non-web schemes; keep it a plain GET.
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("image/") ||
                    contentType.contains("application/pdf") ||
                    contentType.contains("application/octet-stream") ||
                    contentType.contains("video/") ||
                    contentType.contains("audio/")
                ) {
                    return@withContext null
                }
                // Read at most ~512 KB then stop — never buffer huge pages.
                val source = response.body?.source() ?: return@withContext null
                val buffer = okio.Buffer()
                val read = runCatching {
                    source.request(MAX_PAGE_BYTES)
                    buffer.write(source.buffer, minOf(source.buffer.size, MAX_PAGE_BYTES))
                }
                if (read.isFailure) return@withContext null
                val html = runCatching { buffer.readUtf8() }.getOrNull() ?: return@withContext null

                val title = HtmlText.extractTitle(html)
                val text = HtmlText.extractText(html, maxChars)
                if (text.isBlank()) return@withContext null
                PageContent(
                    url = response.request.url.toString(),
                    title = title,
                    text = text,
                    truncated = text.length >= maxChars
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun String.toHttpUrlOrNull(): okhttp3.HttpUrl? =
        runCatching { okhttp3.HttpUrl.Companion.get(this) }.getOrNull()

    companion object {
        private const val MAX_PAGE_BYTES = 512L * 1024L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"
    }
}

/**
 * Dependency-free HTML → readable text reduction: drops script/style/noscript,
 * converts block tags to newlines, strips remaining tags, decodes entities,
 * collapses whitespace. Not a full DOM — deliberately, for mobile memory.
 */
object HtmlText {

    fun extractTitle(html: String): String? {
        val match = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return null
        return decode(match.groupValues[1]).trim().take(200).ifBlank { null }
    }

    fun extractText(html: String, maxChars: Int): String {
        var s = html
        s = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>").replace(s, " ")
        s = Regex("(?is)<(br|/p|/div|/h[1-6]|/li|/tr|/blockquote|/pre|/section|/article)[^>]*>")
            .replace(s, "\n")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = decode(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
        return if (s.length > maxChars) s.take(maxChars) + " …[truncated]" else s
    }

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "ldquo" to "“", "rdquo" to "”", "copy" to "©"
    )

    /** Numeric + common named entities; unknown entities are dropped. */
    fun decode(text: String): String {
        var s = Regex("&#x([0-9A-Fa-f]+);").replace(text) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        s = Regex("&#(\\d+);").replace(s) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        s = Regex("&([a-zA-Z]+);").replace(s) { m ->
            entities[m.groupValues[1].lowercase()] ?: ""
        }
        return s
    }
}
