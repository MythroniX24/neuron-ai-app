package com.neuron.ai.data.workspace

import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * All filesystem operations for one workspace, boundary-validated.
 * Every path is resolved against the workspace root and canonicalized;
 * anything escaping the root is rejected — "../" traversal included.
 */
class WorkspaceContext(
    val workspace: com.neuron.ai.core.workspace.Workspace,
    dispatchers: DispatcherProvider
) {
    private val io = dispatchers.io
    val root: File = File(workspace.rootPath).canonicalFile

    /**
     * Milestone-3 task concurrency (sec 22): READS stay fully concurrent;
     * every WRITE/mutation serializes per workspace, so two agent tasks can
     * never interleave mutating steps on the same project. Files are still
     * read fresh by the writer before each edit — ApplyPatch's anchor
     * validation additionally fails loudly on a changed target.
     */
    private val writeMutex = Mutex()

    /** Resolves [relativePath] inside the root, or null on traversal. */
    fun resolve(relativePath: String): File? {
        val cleaned = relativePath.trim().removePrefix("/").ifBlank { "." }
        val candidate = File(root, cleaned).canonicalFile
        return if (candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    /** Relative path of [file] within the workspace, or null if outside. */
    fun relativePathOf(file: File): String? {
        val canonical = file.canonicalFile
        return if (canonical.path.startsWith(root.path + File.separator)) {
            canonical.path.removePrefix(root.path + File.separator)
        } else {
            null
        }
    }

    suspend fun list(path: String = "."): List<Entry>? = withIo {
        val dir = resolve(path) ?: return@withIo null
        if (!dir.exists() || !dir.isDirectory) return@withIo null
        dir.listFiles()
            ?.map {
                Entry(
                    name = it.name,
                    relativePath = it.relativeToOrNull(dir)?.path ?: it.name,
                    isDirectory = it.isDirectory,
                    sizeBytes = if (it.isFile) it.length() else null,
                    lastModifiedEpochMs = it.lastModified()
                )
            }
            ?.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun readText(path: String, maxBytes: Long = 1_000_000): String? = withIo {
        val file = resolve(path) ?: return@withIo null
        if (!file.isFile) return@withIo null
        // Bounded read: never materialize a whole huge file in memory —
        // read at most maxBytes, then stop pulling from the stream.
        val buffer = java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            val chunk = ByteArray(8_192)
            var total = 0L
            while (total < maxBytes) {
                val toRead = minOf(chunk.size.toLong(), maxBytes - total).toInt()
                val read = input.read(chunk, 0, toRead)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
        }
        val truncated = file.length() > maxBytes
        val text = buffer.toString("UTF-8")
        if (truncated) text + "\n\n… [truncated]" else text
    }

    /** True when the file exceeds the safe edit size (tools refuse to edit it). */
    suspend fun isOversized(path: String, limitBytes: Long = 2_000_000): Boolean = withIo {
        val file = resolve(path) ?: return@withIo false
        file.isFile && file.length() > limitBytes
    }

    suspend fun writeText(path: String, content: String): Boolean = withWriteLock {
        val file = resolve(path) ?: return@withWriteLock false
        file.parentFile?.mkdirs()
        runCatching { file.writeText(content) }.isSuccess
    }

    suspend fun mkdirs(path: String): Boolean = withWriteLock {
        val dir = resolve(path) ?: return@withWriteLock false
        dir.isDirectory || runCatching { dir.mkdirs() }.getOrDefault(false)
    }

    suspend fun rename(from: String, toName: String): Boolean = withWriteLock {
        val source = resolve(from) ?: return@withWriteLock false
        if (!source.exists()) return@withWriteLock false
        // Destination stays inside the source's parent directory.
        val target = File(source.parentFile, toName.trim().removePrefix("/"))
        val inside = target.canonicalFile.path.let { p ->
            p == root.path || p.startsWith(root.path + File.separator)
        }
        if (!inside) return@withWriteLock false
        // Never rename/replace the workspace root itself.
        if (source.path == root.path) return@withWriteLock false
        runCatching { source.renameTo(target) }.getOrDefault(false)
    }

    suspend fun move(from: String, toDir: String): Boolean = withWriteLock {
        val source = resolve(from) ?: return@withWriteLock false
        val destDir = resolve(toDir) ?: return@withWriteLock false
        if (!source.exists() || !destDir.isDirectory) return@withWriteLock false
        val target = File(destDir, source.name)
        if (target.exists()) return@withWriteLock false
        runCatching { source.renameTo(target) }.getOrDefault(false)
    }

    suspend fun copy(from: String, toDir: String): Boolean = withWriteLock {
        val source = resolve(from) ?: return@withWriteLock false
        val destDir = resolve(toDir) ?: return@withWriteLock false
        if (!source.isFile || !destDir.isDirectory) return@withWriteLock false
        val target = File(destDir, source.name)
        if (target.exists()) return@withWriteLock false
        runCatching { source.copyTo(target) }.isSuccess
    }

    suspend fun delete(path: String): Boolean = withWriteLock {
        val file = resolve(path) ?: return@withWriteLock false
        if (!file.exists()) return@withWriteLock false
        // The workspace root itself is never deletable through this context.
        if (file.path == root.path) return@withWriteLock false
        runCatching { file.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * Combined name/path/content search with extension filters and caps.
     * Content scanning skips binary-looking and oversized files; capped at
     * [maxResults] so huge trees never flood memory or the model.
     */
    suspend fun search(
        query: String,
        extensions: List<String> = emptyList(),
        maxResults: Int = 30
    ): List<SearchHit> = withIo {
        if (query.isBlank()) return@withIo emptyList()
        val hits = mutableListOf<SearchHit>()
        val q = query.lowercase()
        val exts = extensions.map { it.removePrefix(".").lowercase() }.toSet()

        root.walkTopDown()
            .onEnter { dir -> dir.listFiles() != null }
            .filter { it.isFile }
            .take(5_000)
            .forEach { file ->
                if (hits.size >= maxResults) return@forEach
                val rel = file.relativeToOrNull(root)?.path ?: return@forEach
                val extOk = exts.isEmpty() || exts.any { rel.endsWith(".$it", ignoreCase = true) }
                if (!extOk) return@forEach

                if (q in file.name.lowercase()) {
                    hits += SearchHit(rel, SearchHit.Kind.NAME)
                    return@forEach
                }
                if (file.length() in 1..512_000 && !looksBinary(file)) {
                    runCatching {
                        if (q in file.readText(Charsets.UTF_8).lowercase()) {
                            hits += SearchHit(rel, SearchHit.Kind.CONTENT)
                        }
                    }
                }
            }
        hits
    }

    suspend fun metadata(path: String): Metadata? = withIo {
        val file = resolve(path) ?: return@withIo null
        if (!file.exists()) return@withIo null
        Metadata(
            relativePath = file.relativeToOrNull(root)?.path ?: file.name,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else null,
            lastModifiedEpochMs = file.lastModified(),
            canRead = file.canRead(),
            canWrite = file.canWrite()
        )
    }

    private fun looksBinary(file: File): Boolean = runCatching {
        val head = file.inputStream().use { stream ->
            val buffer = ByteArray(1024)
            val read = stream.read(buffer)
            buffer.copyOf(read.coerceAtLeast(0))
        }
        head.any { it == 0.toByte() }
    }.getOrDefault(true)

    private suspend fun <T> withIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(io) { block() }

    /** Serializes mutations so concurrent tasks never corrupt workspace data. */
    private suspend fun <T> withWriteLock(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(io) {
            writeMutex.withLock { block() }
        }

    data class Entry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val lastModifiedEpochMs: Long
    )

    data class SearchHit(val relativePath: String, val kind: Kind) {
        enum class Kind { NAME, CONTENT }
    }

    data class Metadata(
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val lastModifiedEpochMs: Long,
        val canRead: Boolean,
        val canWrite: Boolean
    )
}
