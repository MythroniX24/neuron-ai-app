package com.neuron.ai.data.web

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.diagnostics.DiagnosticsLog
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.web.EntityImageResolver
import com.neuron.ai.core.web.ImageQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Counters the Debug console shows for the card-image pipeline. */
data class ImageResolutionStats(
    val lookups: Int = 0,
    val resolved: Int = 0,
    val misses: Int = 0,
    val failures: Int = 0
) {
    val summary: String
        get() = "$resolved resolved / $misses empty / $failures failed of $lookups lookups"
}

/**
 * Keyless image lookup for comparison and recommendation cards.
 *
 * Wikipedia's public action API is the source: it needs no account or key,
 * answers with a stable thumbnail URL for products, phones, apps, models and
 * places, and sends `https` links. Nothing is invented — if the lookup misses,
 * the caller renders the neutral fallback tile.
 *
 * The model's phrase is often too descriptive for an encyclopedia ("Motorola
 * mid-range smartphone (2024)"), so [ImageQuery] tries the full phrase first
 * and then progressively simpler variants. Each variant costs at most one
 * request, results are cached for six hours, and every attempt is recorded in
 * the diagnostics feed so a permanent placeholder is explainable.
 */
class WikipediaImageResolver(
    dispatchers: DispatcherProvider,
    private val logger: Logger? = null,
    httpClient: OkHttpClient? = null,
    private val diagnostics: DiagnosticsLog? = null
) : EntityImageResolver {

    private val io = dispatchers.io

    private val client: OkHttpClient = (httpClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class CacheEntry(val url: String?, val atMs: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE
    }

    private val _stats = MutableStateFlow(ImageResolutionStats())
    val stats: StateFlow<ImageResolutionStats> = _stats.asStateFlow()

    override suspend fun resolve(query: String): String? = withContext(io) {
        val key = ImageQuery.key(query)
        if (key.length < 2) return@withContext null
        synchronized(cache) {
            cache[key]?.let { entry ->
                if (System.currentTimeMillis() - entry.atMs < TTL_MS) return@withContext entry.url
            }
        }
        _stats.value = _stats.value.copy(lookups = _stats.value.lookups + 1)

        var found: String? = null
        var failed = false
        for (variant in ImageQuery.variants(query)) {
            val result = runCatching { lookup(variant) }
            if (result.isFailure) {
                failed = true
                logger?.w("Images", "Image lookup failed for \"$variant\"", result.exceptionOrNull())
            }
            found = result.getOrNull()
            if (!found.isNullOrBlank()) {
                diagnostics?.log("IMAGE", "resolved \"$query\"", "via \"$variant\"")
                break
            }
        }

        synchronized(cache) {
            cache[key] = CacheEntry(found, System.currentTimeMillis())
        }
        _stats.value = _stats.value.let { current ->
            when {
                !found.isNullOrBlank() -> current.copy(resolved = current.resolved + 1)
                failed -> current.copy(failures = current.failures + 1)
                else -> current.copy(misses = current.misses + 1)
            }
        }
        if (found.isNullOrBlank()) {
            diagnostics?.log(
                "IMAGE",
                "no image for \"$query\"",
                ImageQuery.variants(query).joinToString(" | ")
            )
        }
        found
    }

    /** One search request; [query] is already a simplified variant. */
    private fun lookup(query: String): String? {
        val httpUrl = runCatching {
            "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrsearch", query)
                .addQueryParameter("gsrlimit", "2")
                .addQueryParameter("prop", "pageimages")
                .addQueryParameter("piprop", "thumbnail")
                .addQueryParameter("pithumbsize", "640")
                .build()
        }.getOrNull() ?: return null

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (body.length > MAX_BODY_CHARS) return null
            return parseThumbnail(body)
        }
    }

    private fun parseThumbnail(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val query = root["query"] as? JsonObject ?: return null
        val pages = query["pages"] as? JsonArray ?: return null
        pages.forEach { page ->
            val obj = page as? JsonObject ?: return@forEach
            val thumbnail = obj["thumbnail"] as? JsonObject ?: return@forEach
            val source = (thumbnail["source"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!source.isNullOrBlank() && isSafeImageUrl(source)) return source
        }
        null
    }.getOrNull()

    /** Only plain public https images — same rule the parser enforces. */
    private fun isSafeImageUrl(url: String): Boolean {
        if (url.length > 2_048) return false
        val lower = url.lowercase()
        if (!lower.startsWith("https://")) return false
        return !lower.contains(" ") && !lower.contains("javascript:")
    }

    companion object {
        private const val MAX_CACHE = 128
        private const val MAX_BODY_CHARS = 512 * 1024
        private const val TTL_MS = 6 * 60 * 60 * 1_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Wikimedia asks clients to identify themselves; this is also what
         * keeps requests from being throttled.
         */
        private const val USER_AGENT =
            "NeuronAI/1.3 (Android; connector-and-card images)"
    }
}
