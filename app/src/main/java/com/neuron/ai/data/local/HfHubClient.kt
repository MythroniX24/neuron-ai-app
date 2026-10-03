package com.neuron.ai.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Hugging Face Hub client (free API, no key): model SEARCH filtered to GGUF
 * and per-repo FILE LISTING with sizes + sha256 (for post-download integrity
 * verification). Pure JVM + OkHttp — unit-testable against MockWebServer.
 */
class HfHubClient(
    baseUrl: String = "https://huggingface.co",
    client: OkHttpClient? = null
) {

    private val base = baseUrl.trimEnd('/')
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** One grouped search hit: variants listed separately, picked at download. */
    data class SearchResult(
        val repoId: String,
        val downloads: Long,
        val likes: Long,
        val license: String?,
        /** All .gguf files in the repo: name → (size, sha256 or null). */
        val variants: List<Variant>
    ) {
        data class Variant(
            val fileName: String,
            val sizeBytes: Long,
            val sha256: String?
        )

        /** Human quant label parsed from the file name (Q4_K_M etc.). */
        val quantLevels: List<String>
            get() = variants.mapNotNull { v -> quantOf(v.fileName) }.distinct()
    }

    /**
     * Searches the Hub for GGUF models matching [query], most-downloaded
     * first. Only repos that actually carry .gguf files are returned.
     */
    fun search(query: String, limit: Int = 20): List<SearchResult> {
        val url = "$base/api/models?search=${urlEncode(query)}&filter=gguf" +
            "&sort=downloads&direction=-1&limit=$limit"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        val array = json.parseToJsonElement(body).jsonArray
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val repoId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val license = (obj["tags"]?.jsonArray)
                ?.mapNotNull { it.jsonPrimitive.content }
                ?.firstOrNull { it.startsWith("license:") }
                ?.removePrefix("license:")
            SearchResult(
                repoId = repoId,
                downloads = obj["downloads"]?.jsonPrimitive?.longOrNull ?: 0L,
                likes = obj["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
                license = license,
                // Search results don't carry file lists — filled by [files].
                variants = emptyList()
            )
        }.filter { !it.repoId.contains("onnx", ignoreCase = true) }
    }

    /**
     * Lists the .gguf files of a repo with sizes and LFS sha256 hashes.
     * Non-GGUF files (README, configs) are excluded. Kept for callers that
     * only need the files; [listing] additionally reports WHY a repo has none
     * (gated / missing / rate-limited) — the difference between "nothing to
     * download" and "this repo needs a Hugging Face login".
     */
    fun files(repoId: String): List<SearchResult.Variant> = listing(repoId).variants

    /**
     * Repo listing WITH its access verdict. Hugging Face returns 401 for
     * GATED repos (google/gemma-*, most Meta mirrors): the file list is hidden
     * and every download URL 401s. Without this distinction the UI showed an
     * empty list with no explanation and every download failed silently.
     */
    data class RepoListing(
        val variants: List<SearchResult.Variant> = emptyList(),
        /** HTTP status of the tree call (0 when the request never completed). */
        val httpCode: Int = 0,
        /** true when the repo exists but requires accepting a licence/login. */
        val gated: Boolean = false,
        /** Repo does not exist (or was renamed/removed upstream). */
        val notFound: Boolean = false,
        /** Hub refused the request (rate limit / server error). */
        val throttled: Boolean = false,
        /** Network or parse failure — [variants] is empty. */
        val error: String? = null
    ) {
        /** Short, user-facing reason to show instead of an empty file list. */
        val unavailableReason: String?
            get() = when {
                error != null -> error
                gated -> "This model is gated on Hugging Face. Open its repo in a " +
                    "browser, sign in and accept the licence, then try again."
                notFound -> "This model is no longer available at that Hugging Face address."
                throttled -> "Hugging Face is rate-limiting the Hub API. Wait a minute and retry."
                else -> null
            }

        val hasDownloadable: Boolean get() = variants.isNotEmpty()
    }

    /** Fetches [RepoListing] for a repo; never throws. */
    fun listing(repoId: String): RepoListing {
        val url = "$base/api/models/${encodePath(repoId)}/tree/main"
        val response = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (t: Throwable) {
            return RepoListing(error = t.message ?: "Could not reach Hugging Face")
        }
        val code = response.code
        if (!response.isSuccessful) {
            response.close()
            return RepoListing(
                httpCode = code,
                gated = code == 401 || code == 403,
                notFound = code == 404,
                throttled = code == 429 || code >= 500
            )
        }
        val body = response.use { it.body?.string().orEmpty() }
        val variants = try {
            json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
                SearchResult.Variant(
                    fileName = path,
                    sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    sha256 = (obj["lfs"]?.jsonObject)?.get("oid")?.jsonPrimitive?.content
                )
            }
        } catch (t: Throwable) {
            return RepoListing(httpCode = code, error = "Unexpected response from the Hub")
        }
        // Split GGUF shards (-00001-of-00003) can't run standalone; they are
        // filtered OUT of the picker so nobody downloads an unusable half.
        val single = variants.filterNot { isShardFile(it.fileName) }
        if (single.isEmpty() && variants.isNotEmpty()) {
            return RepoListing(
                httpCode = code,
                error = "Only split (multi-shard) GGUF files here — not supported yet."
            )
        }
        return RepoListing(variants = single, httpCode = code)
    }

    /** "-00001-of-00003.gguf" style shard part of a split GGUF. */
    fun isShardFile(fileName: String): Boolean =
        SHARD_PATTERN.containsMatchIn(fileName)

    /** Quant labels of a single file name ("Q4_K_M", "IQ4_XS", "F16"...). */
    fun quantOf(fileName: String): String? =
        Regex("""[._-](I?Q\d[_A-Za-z0-9]*|F16|F32|BF16)[._-]?""")
            .find(fileName)?.groupValues?.get(1)

    /**
     * Picks the variant matching the preferred quant list in order, falling
     * back to the SMALLEST file that is still a sane quality pick. Pure — the
     * recommendation flow resolves files live (names drift upstream), so the
     * catalog only states a PREFERENCE, never a hardcoded file name.
     */
    fun selectVariant(
        variants: List<SearchResult.Variant>,
        preferredQuants: List<String> = DEFAULT_QUANT_PREFERENCE
    ): SearchResult.Variant? {
        if (variants.isEmpty()) return null
        preferredQuants.forEach { want ->
            variants.firstOrNull { quantOf(it.fileName)?.equals(want, ignoreCase = true) == true }
                ?.let { return it }
        }
        // No exact match: prefer a mid-size quant, else the smallest file.
        val quantized = variants.filter { quantOf(it.fileName)?.startsWith("Q") == true }
        val pool = quantized.ifEmpty { variants }
        return pool.minByOrNull { it.sizeBytes }
    }

    /** Direct download URL for a repo file (redirects to the CDN). */
    fun downloadUrl(repoId: String, fileName: String): String =
        "$base/${encodePath(repoId)}/resolve/main/${urlEncode(fileName)}"

    /**
     * Encodes a repo path ("owner/name") segment-by-segment so the "/"
     * separators survive — URLEncoder would turn them into %2F and break
     * both the tree endpoint and the resolve/download URL.
     */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { urlEncode(it) }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private val SHARD_PATTERN = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)

        /**
         * Quantization order for phones: Q4_K_M is the sweet spot (best
         * quality/size), the rest degrade gracefully either way.
         */
        val DEFAULT_QUANT_PREFERENCE = listOf("Q4_K_M", "Q5_K_M", "Q4_K_S", "IQ4_XS", "Q4_0", "Q3_K_M")
    }
}
