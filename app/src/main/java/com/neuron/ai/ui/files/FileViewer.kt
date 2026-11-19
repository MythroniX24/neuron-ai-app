package com.neuron.ai.ui.files

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neuron.ai.ui.components.NeuronAsyncImage
import com.neuron.ai.ui.markdown.SyntaxHighlighter
import com.neuron.ai.ui.theme.LocalAppDarkMode
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing

/** A file the user asked to open from the chat. */
data class OpenFileRequest(
    val path: String,
    val name: String? = null
)

/**
 * Opens files from anywhere below [FileViewerHost] — A2UI file cards, code
 * cards, chat links — without threading a callback through every composable.
 */
val LocalFileOpener = compositionLocalOf<(OpenFileRequest) -> Unit> { {} }

/**
 * One in-app file viewer above the whole app: close cross at the top left,
 * open-in-another-app and download at the top right. HTML files get a
 * Preview / Code switch and open on the preview, so the page renders
 * immediately; code and text files get syntax highlighting.
 */
@Composable
fun FileViewerHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<OpenFileRequest?>(null) }

    CompositionLocalProvider(LocalFileOpener provides { requested -> request = requested }) {
        content()
    }

    request?.let { current ->
        FileViewerDialog(request = current, onClose = { request = null })
    }
}

@Composable
private fun FileViewerDialog(request: OpenFileRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val ref = remember(request) { FileRef.resolve(context, request.path, request.name) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                ViewerHeader(
                    title = ref?.name ?: request.name ?: request.path.substringAfterLast('/'),
                    subtitle = ref?.let { "${it.extension.uppercase().ifBlank { "FILE" }} · ${it.sizeLabel}" },
                    onClose = onClose,
                    onDownload = { ref?.let { downloadToDownloads(context, it) } },
                    onOpenExternally = { ref?.let { openExternally(context, it) } }
                )

                if (ref == null) {
                    MissingFile(request.path, onClose)
                } else {
                    ViewerBody(ref)
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ViewerHeader(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onOpenExternally: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close file")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onOpenExternally) {
            Icon(Icons.Outlined.OpenInNew, contentDescription = "Open with another app")
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = "Download file")
        }
    }
}

@Composable
private fun ViewerBody(ref: FileRef) {
    when {
        ref.isHtml -> HtmlFileBody(ref)
        ref.isImage -> ImageFileBody(ref)
        ref.isTextual -> CodeFileBody(ref)
        else -> NotPreviewable(ref)
    }
}

@Composable
private fun HtmlFileBody(ref: FileRef) {
    var showPreview by remember(ref) { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (showPreview) 0 else 1) {
            Tab(
                selected = showPreview,
                onClick = { showPreview = true },
                text = { Text("Preview") }
            )
            Tab(
                selected = !showPreview,
                onClick = { showPreview = false },
                text = { Text("Code") }
            )
        }
        if (showPreview) WebPreview(ref) else CodeFileBody(ref)
    }
}

@Composable
private fun WebPreview(ref: FileRef) {
    val context = LocalContext.current
    val html = remember(ref) { if (ref.file == null) ref.readText(context) else null }

    Box(
        Modifier
            .fillMaxSize()
            .padding(Spacing.sm)
            .clip(RoundedCornerShape(Radius.md))
            .background(androidx.compose.ui.graphics.Color.White)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowContentAccess = true
                    webViewClient = WebViewClient()
                    if (ref.file != null) {
                        loadUrl(ref.file.toURI().toString())
                    } else {
                        loadDataWithBaseURL(null, html.orEmpty(), "text/html", "utf-8", null)
                    }
                }
            }
        )
    }
}

@Composable
private fun ImageFileBody(ref: FileRef) {
    Box(Modifier.fillMaxSize().padding(Spacing.md), contentAlignment = Alignment.Center) {
        NeuronAsyncImage(
            model = ref.uri,
            contentDescription = ref.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CodeFileBody(ref: FileRef) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dark = LocalAppDarkMode.current
    val text = remember(ref) { ref.readText(context) }
    val highlighted = remember(text, dark, ref.language) {
        SyntaxHighlighter.highlight(
            code = text.orEmpty(),
            language = ref.language,
            dark = dark
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ref.language ?: "text",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(text.orEmpty())) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy file contents",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SelectionContainer(Modifier.fillMaxSize()) {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }
    }
}

@Composable
private fun NotPreviewable(ref: FileRef) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "This file can't be shown inside the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { openExternally(context, ref) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Open")
            }
            OutlinedButton(onClick = { downloadToDownloads(context, ref) }) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Download")
            }
        }
    }
}

@Composable
private fun MissingFile(path: String, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "This file could not be found.",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(onClick = onClose) { Text("Close") }
    }
}
