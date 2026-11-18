package com.neuron.ai.ui.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.neuron.ai.data.storage.DeviceStorage
import java.io.File
import java.io.InputStream

/**
 * A file the chat (or an A2UI card) points at. Paths may be a device path
 * relative to shared storage, an absolute path, a `file://` URI or a
 * `content://` URI from an attachment.
 */
data class FileRef(
    val name: String,
    val uri: Uri,
    val file: File?,
    val sizeBytes: Long?,
    val mime: String?
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val isHtml: Boolean get() = extension in HTML_EXTENSIONS

    val isImage: Boolean
        get() = mime?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS

    val language: String? get() = LANGUAGE_BY_EXTENSION[extension]

    val isTextual: Boolean
        get() = language != null ||
            extension in TEXT_EXTENSIONS ||
            mime?.startsWith("text/") == true

    val sizeLabel: String
        get() = sizeBytes?.takeIf { it > 0 }?.let { bytes ->
            when {
                bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
                bytes >= 1_024 -> "${bytes / 1_024} KB"
                else -> "$bytes B"
            }
        } ?: "unknown size"

    /** File contents for the in-app code/text viewer; null when too big. */
    fun readText(context: Context, limitChars: Int = 400_000): String? = runCatching {
        if ((sizeBytes ?: 0) > 4L * 1_048_576) return null
        openStream(context)?.use { input ->
            input.bufferedReader().use { it.readText() }.take(limitChars)
        }
    }.getOrNull()

    fun openStream(context: Context): InputStream? =
        if (file != null) file.inputStream() else context.contentResolver.openInputStream(uri)

    companion object {
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
        private val IMAGE_EXTENSIONS =
            setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "svg")
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "jsonl", "xml", "csv", "tsv", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "log", "gradle", "kts", "properties", "sql",
            "sh", "bash", "zsh", "bat", "ps1", "env", "gitignore", "lock", "html", "htm",
            "css", "js", "mjs", "ts", "jsx", "tsx", "kt", "java", "py", "go", "rs", "c",
            "h", "cpp", "hpp", "cs", "swift", "dart", "php", "rb", "lua"
        )
        private val LANGUAGE_BY_EXTENSION = mapOf(
            "kt" to "kotlin", "kts" to "kotlin",
            "java" to "java",
            "py" to "python",
            "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
            "ts" to "javascript", "tsx" to "javascript", "jsx" to "javascript",
            "go" to "go",
            "rs" to "rust",
            "html" to "html", "htm" to "html",
            "css" to "css",
            "json" to "json", "jsonl" to "json",
            "md" to "markdown", "markdown" to "markdown",
            "xml" to "xml",
            "sh" to "bash", "bash" to "bash",
            "sql" to "sql",
            "yml" to "yaml", "yaml" to "yaml",
            "txt" to "text", "log" to "text", "csv" to "text"
        )

        /** Resolves any of the path shapes the agent uses into a readable file. */
        fun resolve(context: Context, raw: String, explicitName: String? = null): FileRef? {
            val path = raw.trim()
            if (path.isEmpty()) return null
            if (path.startsWith("http://") || path.startsWith("https://")) return null

            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val meta = queryMeta(context, uri)
                val name = explicitName ?: meta.first ?: "file"
                return FileRef(
                    name = name,
                    uri = uri,
                    file = null,
                    sizeBytes = meta.second,
                    mime = context.contentResolver.getType(uri) ?: mimeFor(name)
                )
            }

            val file = if (path.startsWith("file://")) {
                runCatching { File(Uri.parse(path).path.orEmpty()) }.getOrNull()
            } else {
                candidates(context, path).firstOrNull { it.exists() && it.isFile }
            } ?: return null

            val name = explicitName?.takeIf { it.isNotBlank() } ?: file.name
            return FileRef(
                name = name,
                uri = Uri.fromFile(file),
                file = file,
                sizeBytes = file.length(),
                mime = mimeFor(name)
            )
        }

        private fun candidates(context: Context, path: String): List<File> {
            val direct = File(path)
            val relative = path.removePrefix("/")
            val out = mutableListOf<File>()
            if (direct.isAbsolute) out += direct
            DeviceStorage.root(context)?.let { out += File(it, relative) }
            out += File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                relative
            )
            out += File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                relative
            )
            context.getExternalFilesDir(null)?.let { out += File(it, relative) }
            out += File(context.filesDir, relative)
            return out
        }

        private fun queryMeta(context: Context, uri: Uri): Pair<String?, Long?> = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    null
                }
                name to size
            }
        }.getOrNull() ?: (null to null)

        fun mimeFor(name: String): String? =
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    }
}

/** Saves a device file into the public Downloads folder. */
fun downloadToDownloads(context: Context, ref: FileRef) {
    val name = sanitizeName(ref.name)
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, ref.mime ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Downloads collection unavailable")
            ref.openStream(context)?.use { input ->
                resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            } ?: error("Could not read the file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val target = File(dir, name)
            ref.openStream(context)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not read the file")
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        }
        Toast.makeText(context, "Saved to Downloads/$name", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Could not save this file", Toast.LENGTH_SHORT).show()
    }
}

/** Hands the file to whichever installed app can display it. */
fun openExternally(context: Context, ref: FileRef) {
    runCatching {
        val uri = if (ref.file != null) {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                ref.file
            )
        } else {
            ref.uri
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ref.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "No app can open ${ref.name}", Toast.LENGTH_SHORT).show()
    }
}

private fun sanitizeName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|]"), "_").takeIf { it.isNotBlank() } ?: "neuron-file"
