package com.neuron.ai.ui.components

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy

/**
 * An image the user tapped, opened full-screen above the whole app.
 *
 * [url] is the resolved picture (markdown image or an entity thumbnail),
 * [description] is what the image is (used as the content description), and
 * [fileName] is only set when a caller already knows a good download name.
 */
data class FullScreenImage(
    val url: String,
    val description: String? = null,
    val fileName: String? = null
)

/**
 * Opens images full-screen from anywhere below [ImageViewerHost] (markdown
 * replies, comparison cards, recommendation thumbnails) without threading a
 * callback through every card composable.
 */
val LocalImageOpener = compositionLocalOf<(FullScreenImage) -> Unit> { {} }

/**
 * Hosts the full-screen viewer once, above the app shell, and exposes
 * [LocalImageOpener] to its content. Closing is a single tap on the cross.
 */
@Composable
fun ImageViewerHost(content: @Composable () -> Unit) {
    var image by remember { mutableStateOf<FullScreenImage?>(null) }

    CompositionLocalProvider(
        LocalImageOpener provides { requested -> image = requested }
    ) {
        content()
    }

    image?.let { current ->
        FullScreenImageViewer(image = current, onClose = { image = null })
    }
}

/**
 * Black canvas + the picture at its natural aspect ratio. Close sits at the
 * top-left, download at the top-right, both over a scrim so they stay visible
 * on bright photos.
 */
@Composable
private fun FullScreenImageViewer(image: FullScreenImage, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(image.url)
                    .crossfade(100)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = image.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 72.dp)
            )

            // Close — top left.
            ScrimIconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close image",
                    tint = Color.White
                )
            }

            // Download — top right.
            ScrimIconButton(
                onClick = { downloadImage(context, image) },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "Download image",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ScrimIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(12.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        content()
    }
}

/**
 * Saves through DownloadManager, so the transfer survives the app being
 * backgrounded and lands in the public Downloads folder where the user can
 * find it from any file manager.
 */
private fun downloadImage(context: Context, image: FullScreenImage) {
    val name = image.fileName?.takeIf { it.isNotBlank() } ?: run {
        val segment = runCatching { Uri.parse(image.url).lastPathSegment }
            .getOrNull()
            ?.substringAfterLast('/')
            ?.take(60)
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "neuron-ai-image"
        if (segment.contains('.')) segment else "$segment-${System.currentTimeMillis()}.jpg"
    }

    runCatching {
        val url = image.url.trim()
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "Saving to Downloads/$name", Toast.LENGTH_SHORT).show()
        } else if (url.startsWith("data:", ignoreCase = true) && url.contains(";base64,", ignoreCase = true)) {
            val commaIdx = url.indexOf(',')
            val bytes = android.util.Base64.decode(url.substring(commaIdx + 1), android.util.Base64.DEFAULT)
            val destFile = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
            destFile.writeBytes(bytes)
            Toast.makeText(context, "Saved to Downloads/$name", Toast.LENGTH_SHORT).show()
        } else {
            val localPath = if (url.startsWith("file://", ignoreCase = true)) url.substring(7) else url
            val srcFile = java.io.File(localPath)
            if (srcFile.exists()) {
                val destFile = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                srcFile.copyTo(destFile, overwrite = true)
                Toast.makeText(context, "Saved to Downloads/$name", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Image file not found", Toast.LENGTH_SHORT).show()
            }
        }
    }.onFailure {
        Toast.makeText(context, "Could not save this image: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
