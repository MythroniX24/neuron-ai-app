package com.neuron.ai.data.attachment

import android.content.Context
import android.net.Uri
import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Persists picked files into app-private storage and extracts text where the
 * type allows it. Raw shared-storage URIs are never kept — the content is
 * copied once, then the URI can be released.
 */
class AttachmentStore(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger
) {

    private val io = dispatchers.io
    private val root: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    /** Resolves display name and mime type, then imports. Used by the file picker. */
    suspend fun importFromUri(uri: Uri): Attachment? {
        val resolver = context.contentResolver
        val name = queryDisplayName(uri) ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return import(uri, name, mime)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Copies the content behind [uri] into private storage. */
    suspend fun import(
        uri: Uri,
        displayName: String,
        mimeType: String
    ): Attachment? = withContext(io) {
        try {
            val resolver = context.contentResolver
            val size = resolveSize(uri)
            val kind = classify(displayName, mimeType)
            val safeName = sanitizeName(displayName)
            val attachmentId = "att-" + UUID.randomUUID().toString().take(8)
            val target = File(root, "${attachmentId}_$safeName")

            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null

            Attachment(
                id = attachmentId,
                displayName = displayName,
                mimeType = mimeType.ifBlank { guessMime(displayName) },
                sizeBytes = target.length().takeIf { it > 0 } ?: size,
                localPath = target.relativeTo(context.filesDir).path,
                kind = kind
            )
        } catch (t: Throwable) {
            logger.w("Attachment", "Failed to import attachment: $displayName", t)
            null
        }
    }

    /** Absolute file for an attachment's relative [localPath], or null if missing. */
    fun fileOf(attachment: Attachment): File? =
        File(context.filesDir, attachment.localPath).takeIf { it.exists() }

    /** Raw bytes for an attachment id, or null. Used for vision uploads. */
    suspend fun readBytesById(attachmentId: String): ByteArray? = withContext(io) {
        runCatching {
            root.listFiles()
                .firstOrNull { it.name.startsWith("${attachmentId}_") }
                ?.readBytes()
        }.getOrNull()
    }

    /** Extracts UTF-8 text for text-like attachments, capped at [maxBytes]. */
    suspend fun extractText(attachment: Attachment, maxBytes: Int = 200_000): String? =
        withContext(io) {
            if (!attachment.isText) return@withContext null
            val file = fileOf(attachment) ?: return@withContext null
            runCatching {
                val bytes = file.readBytes()
                val truncated = bytes.size > maxBytes
                val text = String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
                if (truncated) "$text\n\n… [truncated]" else text
            }.getOrNull()
        }

    suspend fun deleteAll(attachments: List<Attachment>) = withContext(io) {
        attachments.forEach { att ->
            runCatching { File(context.filesDir, att.localPath).delete() }
        }
    }

    private fun resolveSize(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

    private fun classify(name: String, mime: String): Attachment.Kind = when {
        mime.startsWith("image/") -> Attachment.Kind.IMAGE
        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> Attachment.Kind.PDF
        isTextLike(name, mime) -> Attachment.Kind.TEXT
        else -> Attachment.Kind.BINARY
    }

    private fun isTextLike(name: String, mime: String): Boolean {
        val textTypes = setOf("text/", "application/json", "application/xml", "application/javascript")
        if (textTypes.any { mime.startsWith(it) }) return true
        val textExtensions = listOf(
            "txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml",
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h",
            "hpp", "go", "rs", "rb", "php", "sh", "bat", "sql", "html", "css", "toml"
        )
        return textExtensions.any { name.endsWith(".$it", ignoreCase = true) }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".md", true) || name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".csv", true) -> "text/csv"
        name.endsWith(".pdf", true) -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }
}
