package com.neuron.ai.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.ChatComposer
import com.neuron.ai.ui.markdown.MarkdownText
import com.neuron.ai.ui.theme.Spacing

private const val EMPTY_HINT =
    "Start the conversation. Your messages stay on this device."

/**
 * Phase 1 chat: persistent conversation with streaming responses, markdown,
 * LaTeX, attachments, model selector and agent activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    onOpenMenu: () -> Unit = {}
) {
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(container, conversationId)
    )

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val generation by viewModel.generationState.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val draftAttachments by viewModel.draftAttachments.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) { viewModel.loadConversation() }

    // Follow the stream: scroll when message count or streaming text changes.
    val streamingText = (generation as? GenerationState.Streaming)?.buffer.orEmpty()
    LaunchedEffect(messages.size, streamingText.length, activity.size) {
        val target = messages.size + if (generation is GenerationState.Streaming) 1 else 0
        if (target > 0) {
            listState.animateScrollToItem(target - 1)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importAttachment(uri)
        }
    }

    val isStreaming = generation is GenerationState.Streaming

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        ChatTopBar(
            modelName = conversation?.modelId,
            modelOptions = viewModel.modelOptions.collectAsStateWithLifecycle().value,
            selectedModelId = conversation?.modelId,
            selectedProviderId = conversation?.providerId,
            onSelectModel = { providerId, modelId -> viewModel.setModel(providerId, modelId) },
            onOpenMenu = onOpenMenu
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (messages.isEmpty() && generation is GenerationState.Idle) {
                item {
                    Text(
                        text = EMPTY_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xxl)
                    )
                }
            }

            items(messages, key = { it.id }) { message ->
                // Subtle entrance for new rows; cheap on scrolling performance.
                Box(Modifier.animateItem()) { MessageRow(message) }
            }

            if (activity.isNotEmpty()) {
                item { AgentActivityCard(activity) }
            }

            when (val gen = generation) {
                is GenerationState.Streaming -> item {
                    StreamingBubble(gen.buffer.ifEmpty { "…" })
                }

                is GenerationState.Failed -> item {
                    ErrorBanner(
                        // error.message carries the provider-specific reason;
                        // userMessage would collapse everything to one generic line.
                        message = gen.error.message,
                        onRetry = { viewModel.retry() },
                        onDismiss = { viewModel.clearError() }
                    )
                }

                GenerationState.Idle -> Unit
            }
        }

        // Draft attachment row with remove affordances.
        if (draftAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                draftAttachments.take(3).forEach { attachment ->
                    AssistChip(
                        onClick = { viewModel.removeDraftAttachment(attachment.id) },
                        label = { Text(attachment.displayName, maxLines = 1) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Remove ${attachment.displayName}",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }

        ComposerBar(
            draft = draft,
            onDraftChange = { draft = it },
            onAttach = {
                filePicker.launch(arrayOf("*/*"))
            },
            onSend = {
                viewModel.send(draft)
                draft = ""
            },
            onStop = viewModel::stop,
            onRegenerate = viewModel::regenerate,
            isStreaming = isStreaming,
            canRegenerate = messages.any { it.role == Message.Role.ASSISTANT },
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelName: String?,
    modelOptions: List<ModelOption>,
    selectedModelId: String?,
    selectedProviderId: String?,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onOpenMenu: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column(
                modifier = Modifier.clickable(enabled = modelOptions.isNotEmpty()) {
                    menuExpanded = true
                }
            ) {
                Text(
                    text = modelName ?: "Select a model",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Tap to switch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Switch model",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenMenu) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "Open menu"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )

    DropdownMenu(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false }
    ) {
        if (modelOptions.isEmpty()) {
            DropdownMenuItem(
                text = {
                    Text(
                        "No models yet — add a provider in Settings → AI Providers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {}
            )
        } else {
            modelOptions.forEach { option ->
                val selected = option.modelId == selectedModelId &&
                    (selectedProviderId == null || option.providerId == selectedProviderId)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.modelId, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Selected",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        onSelectModel(option.providerId, option.modelId)
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageRow(message: Message) {
    when (message.role) {
        Message.Role.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (message.attachments.isNotEmpty()) {
                    Text(
                        text = "📎 ${message.attachments.size} attachment(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    )
                }
            }
        }

        Message.Role.TOOL -> Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(Spacing.md)) {
                Text(
                    text = "🛠 ${message.metadata?.toolName ?: "Tool"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = message.content.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }

        Message.Role.ASSISTANT -> Column(
            // Subtle inset so AI text never touches the screen edges; no
            // visible border, just breathing room.
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.sm, end = Spacing.sm)
        ) {
            MarkdownText(markdown = message.content)
        }

        Message.Role.SYSTEM -> Unit
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.sm, end = Spacing.sm)
    ) {
        MarkdownText(markdown = text)
    }
}

/**
 * The composer: attach, text input, and a single trailing action that
 * morphs with state (attach+send when idle, stop while streaming,
 * regenerate after a failure).
 */
@Composable
private fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    isStreaming: Boolean,
    canRegenerate: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAttach, enabled = !isStreaming) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Attach file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Neuron…") },
                enabled = !isStreaming,
                minLines = 1,
                maxLines = 5,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyLarge
            )
            when {
                isStreaming -> IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Stop generating",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                draft.isNotBlank() -> IconButton(onClick = onSend) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                canRegenerate -> IconButton(onClick = onRegenerate) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Regenerate",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentActivityCard(steps: List<AgentActivityUi>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(Spacing.md)) {
            steps.forEach { step ->
                val icon = when (step.state) {
                    AgentActivityUi.AgentActivityState.DONE -> "✓"
                    AgentActivityUi.AgentActivityState.FAILED -> "✗"
                    AgentActivityUi.AgentActivityState.RUNNING -> "⟳"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (step.state) {
                            AgentActivityUi.AgentActivityState.DONE ->
                                MaterialTheme.colorScheme.primary
                            AgentActivityUi.AgentActivityState.FAILED ->
                                MaterialTheme.colorScheme.error
                            AgentActivityUi.AgentActivityState.RUNNING ->
                                MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = " ${step.title}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = Spacing.xs)
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.sm)
            )
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
