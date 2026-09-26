package com.neuron.ai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.data.terminal.TerminalLine
import com.neuron.ai.data.terminal.TerminalSession
import com.neuron.ai.data.terminal.TerminalState
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Real terminal panel over the chat: rendered inside a
 * [com.neuron.ai.ui.components.DraggablePanelContainer] — drag the strip up
 * for full screen, down to close. Streams the shared [TerminalSession]
 * output — the same session AI tools use. Monospace, stdout/stderr distinct.
 */
@Composable
fun TerminalPanel(
    session: TerminalSession,
    onDismiss: () -> Unit
) {
    var command by remember { mutableStateOf("") }
    val output by session.output.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val workingDir by session.workingDirFlow.collectAsStateWithLifecycle()
    val lastExit by session.lastExitCode.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Follow output; auto-scroll like a real terminal.
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) listState.animateScrollToItem(output.size - 1)
    }

    val isRunning = state == TerminalState.RUNNING

    // Drag-up-to-fullscreen container owns the height and the drag strip;
    // this composable just renders the panel content below it.
    com.neuron.ai.ui.components.DraggablePanelContainer(onDismiss = onDismiss) {
        // Dim scrim BEHIND the panel: chat content no longer visually
        // "overlaps" the terminal — the panel reads as a raised layer.
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
        )
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f)
        ) {
            Column(
                Modifier
                    .imePadding()
                    .fillMaxHeight()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                ) {
                    Text(
                        text = "Terminal",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { session.clearOutput() }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Clear output",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(output.joinToString("\n") { it.text })
                            )
                        }
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy output",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close terminal",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // Working directory + last exit code.
                Text(
                    text = workingDir.path + (lastExit?.let { "  ·  last exit $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = Spacing.lg)
                )

                // ---- Output -----------------------------------------------------
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(output) { line ->
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = when (line.stream) {
                                TerminalLine.Stream.STDERR -> MaterialTheme.colorScheme.error
                                TerminalLine.Stream.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                // ---- Command input ------------------------------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                ) {
                    Text(
                        text = "$",
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = { Text("command…", style = MaterialTheme.typography.bodySmall) },
                        enabled = !isRunning,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f)
                    )
                    if (isRunning) {
                        IconButton(onClick = { session.stop() }) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = "Stop command",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val cmd = command.trim()
                                if (cmd.isNotEmpty()) {
                                    command = ""
                                    scope.launch { session.execute(cmd) }
                                }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Run command",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
