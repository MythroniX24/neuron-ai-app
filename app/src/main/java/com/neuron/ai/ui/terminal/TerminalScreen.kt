package com.neuron.ai.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.data.terminal.TerminalLine
import com.neuron.ai.data.terminal.TerminalSession
import com.neuron.ai.data.terminal.TerminalState
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File

private val TerminalBg = Color(0xFF0C0D0E)
private val TerminalSurface = Color(0xFF14171A)
private val TerminalBorder = Color(0xFF22272E)
private val TerminalGreen = Color(0xFF10B981)
private val TerminalCyan = Color(0xFF38BDF8)
private val TerminalYellow = Color(0xFFFBBF24)
private val TerminalRed = Color(0xFFF87171)
private val TerminalText = Color(0xFFE5E7EB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val session: TerminalSession = remember {
        val workspaceDir = File(context.filesDir, "workspace").apply {
            if (!exists()) mkdirs()
        }
        container.terminalManager.sessionFor("termux-fullscreen", null, workspaceDir)
    }

    val output by session.output.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val workingDir by session.workingDirFlow.collectAsStateWithLifecycle()
    val lastExit by session.lastExitCode.collectAsStateWithLifecycle()
    val isRunning = state == TerminalState.RUNNING

    var inputState by remember { mutableStateOf(TextFieldValue("")) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val history = remember(output) { session.historySnapshot() }

    val listState = rememberLazyListState()

    // Auto-scroll when new output arrives
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            listState.animateScrollToItem(output.size - 1)
        }
    }

    fun submitCommand() {
        val cmd = inputState.text.trim()
        if (cmd.isNotEmpty() && !isRunning) {
            inputState = TextFieldValue("")
            historyIndex = -1
            scope.launch {
                session.execute(cmd)
            }
        }
    }

    fun historyUp() {
        if (history.isEmpty()) return
        val nextIndex = if (historyIndex == -1) history.size - 1 else (historyIndex - 1).coerceAtLeast(0)
        historyIndex = nextIndex
        val target = history[nextIndex]
        inputState = TextFieldValue(target, androidx.compose.ui.text.TextRange(target.length))
    }

    fun historyDown() {
        if (history.isEmpty() || historyIndex == -1) return
        val nextIndex = historyIndex + 1
        if (nextIndex < history.size) {
            historyIndex = nextIndex
            val target = history[nextIndex]
            inputState = TextFieldValue(target, androidx.compose.ui.text.TextRange(target.length))
        } else {
            historyIndex = -1
            inputState = TextFieldValue("")
        }
    }

    fun insertText(text: String) {
        val current = inputState.text
        val selection = inputState.selection
        val newText = current.substring(0, selection.start) + text + current.substring(selection.end)
        val newPos = selection.start + text.length
        inputState = TextFieldValue(newText, androidx.compose.ui.text.TextRange(newPos))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            // Header TopAppBar
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isRunning) TerminalYellow else TerminalGreen,
                            modifier = Modifier.size(10.dp)
                        ) {}

                        Column {
                            Text(
                                text = "Neuron Termux Shell",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = TerminalText
                            )
                            Text(
                                text = workingDir.path.replace(context.filesDir.path, "~"),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = TerminalCyan,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = TerminalText
                        )
                    }
                },
                actions = {
                    if (isRunning) {
                        IconButton(onClick = { session.stop() }) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = "Interrupt (^C)",
                                tint = TerminalRed
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(output.joinToString("\n") { it.text }))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy output",
                            tint = TerminalText.copy(alpha = 0.7f)
                        )
                    }

                    IconButton(onClick = { session.clearOutput() }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Clear",
                            tint = TerminalText.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TerminalSurface
                )
            )

            // Terminal Console Output Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (output.isEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(vertical = Spacing.lg)) {
                            Text(
                                text = "Neuron Linux & Termux Environment v2.4",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TerminalGreen
                                )
                            )
                            Text(
                                text = "Type commands below or use virtual accessory keys. Run 'help' or explore files in workspace.",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = TerminalText.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                }

                items(output) { line ->
                    val color = when {
                        line.stream == TerminalLine.Stream.STDERR -> TerminalRed
                        line.text.startsWith("$ ") -> TerminalCyan
                        line.text.startsWith("^C") -> TerminalYellow
                        line.stream == TerminalLine.Stream.SYSTEM -> TerminalGreen
                        else -> TerminalText
                    }

                    Text(
                        text = line.text,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = color
                        )
                    )
                }
            }

            // Virtual Accessory Keys Toolbar (Termux Extra Keys)
            Surface(
                color = TerminalSurface,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, TerminalBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccessoryKey(label = "ESC") { inputState = TextFieldValue("") }
                    AccessoryKey(label = "TAB") { insertText("    ") }
                    AccessoryKey(label = "CTRL-C", color = TerminalRed) { session.stop() }
                    AccessoryKey(label = "↑", color = TerminalCyan) { historyUp() }
                    AccessoryKey(label = "↓", color = TerminalCyan) { historyDown() }
                    AccessoryKey(label = "clear") { session.clearOutput() }
                    AccessoryKey(label = "/") { insertText("/") }
                    AccessoryKey(label = "-") { insertText("-") }
                    AccessoryKey(label = "~") { insertText("~") }
                    AccessoryKey(label = "|") { insertText("|") }
                    AccessoryKey(label = "&") { insertText("&") }
                    AccessoryKey(label = ";") { insertText(";") }
                    AccessoryKey(label = ">") { insertText(">") }
                }
            }

            // Command Prompt Row
            Surface(
                color = TerminalBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    Text(
                        text = "neuron:~$ ",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TerminalGreen
                        )
                    )

                    BasicTextField(
                        value = inputState,
                        onValueChange = { inputState = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = TerminalText
                        ),
                        cursorBrush = SolidColor(TerminalGreen),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send,
                            autoCorrectEnabled = false
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { submitCommand() }
                        )
                    )

                    IconButton(
                        onClick = { submitCommand() },
                        enabled = inputState.text.isNotBlank() && !isRunning,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Run",
                            tint = if (inputState.text.isNotBlank() && !isRunning) TerminalGreen else TerminalText.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    color: Color = TerminalText,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = TerminalBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, TerminalBorder),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = color
                )
            )
        }
    }
}
