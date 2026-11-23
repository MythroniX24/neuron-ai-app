package com.neuron.ai.ui.components

import android.content.Context
import android.os.Environment
import android.util.Base64
import coil.ImageLoader
import coil.disk.DiskCache
import coil.map.Mapper
import coil.memory.MemoryCache
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ultra-fast, high-performance image engine for Neuron AI.
 *
 * Provides sub-millisecond in-memory cached image retrieval, aggressive 256MB disk caching,
 * automatic decoding of AI-generated Base64 data URIs and raw Base64 strings,
 * local storage file path resolution, and an optimized OkHttp client with HTTP connection pooling.
 */
object NeuronImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    fun build(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.35) // 35% of heap for instant in-memory cache
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("neuron_image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024) // 256 MB disk cache
                    .build()
            }
            .components {
                add(Base64ImageMapper())
                add(FilePathMapper())
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(18, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .addNetworkInterceptor { chain ->
                        val original = chain.request()
                        val reqBuilder = original.newBuilder()
                        if (original.header("User-Agent") == null) {
                            reqBuilder.header(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            )
                        }
                        if (original.header("Accept") == null) {
                            reqBuilder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        }
                        chain.proceed(reqBuilder.build())
                    }
                    .build()
            }
            .allowHardware(true)
            .respectCacheHeaders(false) // aggressively reuse cached images for instant loading
            .dispatcher(Dispatchers.IO)
            .interceptorDispatcher(Dispatchers.IO)
            .build()
    }
}

/**
 * Maps Base64 strings and `data:image/...;base64,...` data URIs to raw [ByteArray],
 * which Coil decodes natively with its built-in ByteArrayFetcher.
 */
class Base64ImageMapper : Mapper<String, ByteArray> {

    override fun map(data: String, options: Options): ByteArray? {
        val trimmed = data.trim()

        // 1. data:image/...;base64, or data:...;base64,
        if (trimmed.startsWith("data:", ignoreCase = true) && trimmed.contains(";base64,", ignoreCase = true)) {
            val commaIdx = trimmed.indexOf(',')
            if (commaIdx != -1) {
                val base64Payload = trimmed.substring(commaIdx + 1).trim()
                return decodeBase64Safe(base64Payload)
            }
        }

        // 2. Raw Base64 string from AI models or image tools (starts with known image magic signatures)
        if (trimmed.length > 80 && !trimmed.contains(" ") && !trimmed.startsWith("http", ignoreCase = true) && !trimmed.startsWith("/")) {
            // PNG magic: iVBORw0KGgo
            // JPEG magic: /9j/
            // WebP magic: UklGR
            // GIF magic: R0lGOD
            // BMP magic: Qk
            if (trimmed.startsWith("iVBORw0KGgo") ||
                trimmed.startsWith("/9j/") ||
                trimmed.startsWith("UklGR") ||
                trimmed.startsWith("R0lGOD") ||
                trimmed.startsWith("Qk")
            ) {
                return decodeBase64Safe(trimmed)
            }
        }

        return null
    }

    private fun decodeBase64Safe(encoded: String): ByteArray? {
        return runCatching {
            // Clean unescaped newlines or whitespace if present
            val sanitized = encoded.replace("\n", "").replace("\r", "").replace(" ", "")
            Base64.decode(sanitized, Base64.DEFAULT)
        }.getOrNull()
    }
}

/**
 * Maps raw file paths (e.g. `/storage/emulated/0/...` or `file:///...`) or relative storage paths
 * to [java.io.File], enabling Coil's native high-speed FileFetcher with NIO channel streaming.
 */
class FilePathMapper : Mapper<String, File> {

    override fun map(data: String, options: Options): File? {
        val trimmed = data.trim()

        // 1. Absolute Linux/Android filesystem paths
        if (trimmed.startsWith("/")) {
            val file = File(trimmed)
            if (file.exists()) return file
        }

        // 2. file:// URIs
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val cleanPath = trimmed.substring(7)
            val file = File(cleanPath)
            if (file.exists()) return file
        }

        // 3. Relative device paths (e.g. "Download/pic.png", "Pictures/screen.jpg", or app filesDir)
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("content://", ignoreCase = true) &&
            !trimmed.startsWith("data:", ignoreCase = true)
        ) {
            val ext = trimmed.substringAfterLast('.', "").lowercase()
            if (ext in listOf("png", "jpg", "jpeg", "webp", "gif", "svg", "bmp")) {
                // Check relative to external storage root (e.g. /storage/emulated/0/)
                val extFile = File(Environment.getExternalStorageDirectory(), trimmed)
                if (extFile.exists()) return extFile

                // Check relative to app internal files directory
                val internalFile = File(options.context.filesDir, trimmed)
                if (internalFile.exists()) return internalFile

                // Check relative to app cache directory
                val cacheFile = File(options.context.cacheDir, trimmed)
                if (cacheFile.exists()) return cacheFile
            }
        }

        return null
    }
}
