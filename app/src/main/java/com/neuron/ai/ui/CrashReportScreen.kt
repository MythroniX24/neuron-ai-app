package com.neuron.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neuron.ai.core.error.CrashRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the previous run's crash report — FULL SCREEN, before anything else.
 *
 * This is the "force the error into view" half of the crash work: whatever
 * killed the process (a Kotlin exception, a native llama.cpp/ggml abort, a GPU
 * driver segfault) left a report on disk, and the user sees it here on the
 * next launch instead of a silent disappearance. The text is selectable, and
 * Copy / Share turn it into something that can be pasted anywhere.
 *
 * Renders nothing when the last run ended cleanly.
 */
@Composable
fun CrashReportGate(context: Context = LocalContext.current) {
    var report by remember { mutableStateOf<String?>(null) }

    // Reading the file touches disk; never on the main thread.
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { CrashRecorder.pendingReport(context) }
    }

    val text = report
    if (!text.isNullOrBlank()) {
        CrashReportDialog(
            report = text,
            onDismiss = {
                CrashRecorder.clear(context)
                report = null
            }
        )
    }
}

@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Dialog(
        // Deliberately NOT dismissible by tapping outside: the report is the
        // first thing the user needs to see, and an accidental tap would throw
        // away the only copy. The buttons are the way out.
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "The app crashed last time",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "This is the exact reason it stopped, captured before the " +
                        "process died. Copy or share it if you want it looked at.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    SelectionContainer {
                        Text(
                            text = report,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, report)
                            copied = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (copied) "Copied" else "Copy")
                    }
                    OutlinedButton(
                        onClick = { share(context, report) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share")
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Neuron-AI crash report", text))
    }
}

private fun share(context: Context, text: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Neuron-AI crash report")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share crash report"))
    }
}
