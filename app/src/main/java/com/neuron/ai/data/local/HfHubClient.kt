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
            get() = variants.mapNotNull { v ->
                Regex("""[._-](I?Q\d[_A-Za-z0-9]*|F16|F32|BF16)[._-]?""")
                    .find(v.fileName)?.groupValues?.get(1)
            }.distinct()
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
     * Non-GGUF files (README, configs) are excluded.
     */
    fun files(repoId: String): List<SearchResult.Variant> {
        val url = "$base/api/models/${urlEncode(repoId)}/tree/main"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        val array = json.parseToJsonElement(body).jsonArray
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            // Split GGUF shards (-00001-of-00003) are handled by the download
            // manager later; for now single-file entries only.
            SearchResult.Variant(
                fileName = path,
                sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                sha256 = (obj["lfs"]?.jsonObject)?.get("oid")
                    ?.jsonPrimitive?.content
            )
        }
    }

    /** Direct download URL for a repo file (redirects to the CDN). */
    fun downloadUrl(repoId: String, fileName: String): String =
        "$base/${urlEncode(repoId)}/resolve/main/${urlEncode(fileName)}"

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
