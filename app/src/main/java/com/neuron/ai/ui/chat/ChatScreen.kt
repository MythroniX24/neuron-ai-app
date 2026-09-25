package com.neuron.ai.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.ui.components.BrandMark
import com.neuron.ai.ui.components.SkeletonBar
import com.neuron.ai.ui.components.entrancePop
import com.neuron.ai.ui.components.messageEntrance
import com.neuron.ai.ui.components.neuronPulse
import com.neuron.ai.ui.components.pressScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.ChatComposer
import com.neuron.ai.ui.markdown.MarkdownText
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.launch

private const val EMPTY_HINT =
    "Start the conversation. Your messages stay on this device."

internal fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Working late"
    }
}

/**
 * Phase 1 chat: persistent conversation with streaming responses, markdown,
 * LaTeX, attachments, model selector and agent activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    onOpenMenu: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenConversations: () -> Unit = {}
) {
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(container, conversationId)
    )

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val generation by viewModel.generationState.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val draftAttachments by viewModel.draftAttachments.collectAsStateWithLifecycle()
    val attachmentNotice by viewModel.attachmentNotice.collectAsStateWithLifecycle()
    val queuedMessage by viewModel.queuedMessage.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Message ids that already played their entrance animation.
    val entranceSeen = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    LaunchedEffect(viewModel) { viewModel.loadConversation() }

    // First send on a brand-new chat: swap this entry for the real
    // conversation — the SAME ChatScreen composable renders it, so the user
    // never lands on a "different screen" mid-conversation.
    // Follow the stream: scroll when message count or streaming text changes.
    val streamingText = (generation as? GenerationState.Streaming)?.buffer.orEmpty()
    LaunchedEffect(messages.size, streamingText.length, activity.size) {
        val target = messages.size + if (generation is GenerationState.Streaming) 1 else 0
        if (target > 0) {
            listState.animateScrollToItem(target - 1)
        }
    }

    val isStreaming = generation is GenerationState.Streaming

    // ---- Terminal + Browser panel state -------------------------------------------
    val workspaces by viewModel.workspaceList.collectAsStateWithLifecycle(initialValue = emptyList())
    val terminalEnabled by viewModel.terminalEnabled.collectAsStateWithLifecycle()
    val browserEnabled by viewModel.browserEnabled.collectAsStateWithLifecycle()
    val activeWorkspaceId = conversation?.workspaceId
    var showTerminal by remember { mutableStateOf(false) }
    var terminalSession by remember { mutableStateOf<com.neuron.ai.data.terminal.TerminalSession?>(null) }
    val screenScope = rememberCoroutineScope()

    // ---- Attachment entry points (sheet: Camera / Photos / Files) ----------------
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var pendingCaptureFile by remember { mutableStateOf<java.io.File?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importAttachment(uri)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importAttachment(uri)
        }
    }

    // Camera capture state must SURVIVE the activity recreation that happens
    // while the camera app is in the foreground — otherwise the capture file
    // (and the whole flow) is lost and the photo never lands in the draft.
    var pendingCapturePath by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf("")
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val path = pendingCapturePath
        pendingCapturePath = ""
        if (captured && path.isNotEmpty()) {
            viewModel.importCameraCapture(java.io.File(path))
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val path = pendingCapturePath
        if (granted && path.isNotEmpty()) {
            cameraLauncher.launch(
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    java.io.File(path)
                )
            )
        } else if (!granted) {
            pendingCapturePath = ""
        }
    }

    val launchCamera: () -> Unit = {
        val file = container.attachmentStore.createCaptureDestination()
        pendingCapturePath = file.absolutePath
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraLauncher.launch(
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file
                )
            )
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxSize()) {
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
            onOpenMenu = onOpenMenu,
            isThinking = isStreaming,
            terminalEnabled = terminalEnabled,
            onOpenTerminal = {
                val existing = terminalSession
                if (existing != null) {
                    showTerminal = true
                } else {
                    screenScope.launch {
                        terminalSession = viewModel.terminalSession()
                        showTerminal = true
                    }
                }
            }
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
                    // Unified-surface greeting: same calm hero the old Home had,
                    // now inline in the one-and-only chat screen.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.xxl)
                    ) {
                        Text(
                            text = greeting(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = "What can I help you with?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                // Entrance choreography runs ONCE per freshly-sent message:
                // recycled rows (scrolled back into view) render statically.
                val animateIn = remember(message.id) {
                    message.id !in entranceSeen && (message.id == messages.lastOrNull()?.id)
                }
                remember(message.id) { entranceSeen.add(message.id) }
                Box(
                    Modifier
                        .animateItem()
                        .messageEntrance(fromEnd = message.role == Message.Role.USER, enabled = animateIn)
                ) {
                    MessageRow(
                        message = message,
                        onRetryFromHere = { viewModel.retryFrom(message.id) },
                        onEditAndResend = { viewModel.editAndResend(message.id, message.content) }
                    )
                }
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

        // Visible reason when an attachment fails to import — never silent.
        attachmentNotice?.let { notice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::clearAttachmentNotice) { Text("Dismiss") }
            }
        }

        // Draft attachment tiles above the text box — rounded squares.
        // Images show the actual thumbnail; files show their name. Tap = remove.
        if (draftAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                draftAttachments.take(4).forEach { attachment ->
                    Box {
                        MessageAttachmentTile(attachment)
                        val removeInteraction = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        }
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(18.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .pressScale(removeInteraction, pressedScale = 0.8f)
                                .clickable(
                                    interactionSource = removeInteraction,
                                    indication = null
                                ) { viewModel.removeDraftAttachment(attachment.id) }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Remove ${attachment.displayName}",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(3.dp)
                            )
                        }
                    }
                }
            }
        }

        // Queued-while-streaming indicator with an undo affordance.
        queuedMessage?.let { queued ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                Text(
                    text = "⏳ Queued: $queued",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::cancelQueuedMessage) { Text("Undo") }
            }
        }

        ComposerBar(
            draft = draft,
            onDraftChange = { draft = it },
            onAttach = { showAttachmentSheet = true },
            onSend = {
                viewModel.send(draft)
                draft = ""
            },
            onStop = viewModel::stop,
            onRegenerate = viewModel::regenerate,
            // The composer stays USABLE while streaming: the user can keep
            // typing and attaching; sending queues the next turn instead of
            // dropping it. Only the send action swaps to Stop.
            isStreaming = isStreaming,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        )
    }

    if (showAttachmentSheet) {
        // Custom presentation: dim scrim + bottom-anchored panel (replaces the
        // default ModalBottomSheet for a fully hand-designed look). The panel
        // slides up / fades out with the scrim — no hard pop.
        androidx.compose.animation.AnimatedVisibility(
            visible = showAttachmentSheet,
            enter = androidx.compose.animation.fadeIn(tween(160)),
            exit = androidx.compose.animation.fadeOut(tween(180))
        ) {
            Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .clickable(indication = null, interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }) { showAttachmentSheet = false }
            )
            // Drag-up-for-fullscreen panel — same gesture language as the
            // terminal: strip up = grow, strip down past the threshold = close.
            com.neuron.ai.ui.components.DraggablePanelContainer(
                onDismiss = { showAttachmentSheet = false },
                restFraction = 0.62f,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
            Box(Modifier.navigationBarsPadding()) {
                AttachmentSheet(
            onDismiss = { showAttachmentSheet = false },
            onCamera = launchCamera,
            onPhotos = {
                photoPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onFiles = { filePicker.launch(arrayOf("*/*")) },
            terminalEnabled = terminalEnabled,
            onToggleTerminal = { viewModel.setTerminalEnabled(it) },
            browserEnabled = browserEnabled,
            onToggleBrowser = { viewModel.setBrowserEnabled(it) },
            workspaces = workspaces,
            activeWorkspaceId = activeWorkspaceId,
            onAttachWorkspace = { viewModel.attachWorkspace(it) },
            onDetachWorkspace = { viewModel.detachWorkspace() },
            onCreateWorkspace = {
                viewModel.createWorkspace("Workspace") { }
            }
                )
            }
            }
        }
        }
    }

    // ---- Draggable terminal panel over the chat (inside the overlay Box) --------
        terminalSession?.let { session ->
        // Slide-up/down entrance instead of a hard pop — the panel feels like
        // it slides out of the composer, not teleporting in. The alignment
        // MUST sit on AnimatedVisibility itself (the direct Box child) — an
        // align deeper in the content is ignored and the panel lands on top.
        androidx.compose.animation.AnimatedVisibility(
            visible = showTerminal,
            enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(200)),
            exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(Modifier.systemBarsPadding()) {
                TerminalPanel(
                    session = session,
                    onDismiss = { showTerminal = false }
                )
            }
        }
    }
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
    onOpenMenu: () -> Unit,
    isThinking: Boolean,
    terminalEnabled: Boolean,
    onOpenTerminal: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Window-space bounds of the title block — the dropdown anchors to its
    // bottom edge so it opens EXACTLY under the header text.
    var titleBounds by remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Signature: the brand mark breathes while the agent works.
                BrandMark(
                    Modifier
                        .padding(end = Spacing.sm)
                        .neuronPulse(enabled = isThinking)
                )
                Column(
                    modifier = Modifier
                        .onGloballyPositioned { titleBounds = it.boundsInWindow() }
                        .clickable(enabled = modelOptions.isNotEmpty()) {
                            menuExpanded = true
                        }
                ) {
                Text(
                    text = shortModelName(modelName),
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
        actions = {
            // Terminal icon ONLY while the capability is enabled for this chat.
            if (terminalEnabled) {
                IconButton(onClick = onOpenTerminal) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = "Open terminal panel"
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )

    if (menuExpanded) {
        // Custom dropdown card — anchored to the measured title block so its
        // top edge sits immediately below the header ("header ke niche"),
        // never near the composer.
        val bounds = titleBounds
        if (bounds != null) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember(bounds) { TitleDropdownProvider(bounds) },
                onDismissRequest = { menuExpanded = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
            ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .widthIn(min = 240.dp, max = 320.dp)
            ) {
                Column(Modifier.padding(vertical = Spacing.sm)) {
                    Text(
                        text = "Select model",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    )
                    if (modelOptions.isEmpty()) {
                        Text(
                            "No models yet — add a provider in Settings → AI Providers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            modelOptions.forEach { option ->
                                val selected = option.modelId == selectedModelId &&
                                    (selectedProviderId == null || option.providerId == selectedProviderId)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelectModel(option.providerId, option.modelId)
                                            menuExpanded = false
                                        }
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            shortModelName(option.modelId),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            option.providerName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // Popup content
        } // if (bounds != null)
    } // if (menuExpanded)
}

/**
 * Positions the model dropdown with its top-left at the measured title
 * block's bottom-left — hugging the header, fully on-screen.
 */
private class TitleDropdownProvider(
    private val anchor: androidx.compose.ui.geometry.Rect
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val x = anchor.left.toInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchor.bottom.toInt() + 4)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/**
 * Strips the provider/namespace prefix from a raw model id for display:
 * "openai/gpt-oss-120b" → "gpt-oss-120b", "accounts/fireworks/models/llama" →
 * "llama". The full id stays in use internally — this is label-only.
 */
private fun shortModelName(rawModelId: String?): String {
    if (rawModelId.isNullOrBlank()) return "Select a model"
    return rawModelId.substringAfterLast('/').ifBlank { rawModelId }
}

/**
 * One attachment tile for a [message] row — the sent-file strip in chat.
 * Images render the thumbnail; other files render a document tile with the
 * file name (never a fake preview).
 */
@Composable
private fun MessageAttachmentTile(attachment: Attachment) {
    val context = LocalContext.current
    val model = remember(attachment.id) {
        attachment.takeIf { it.isImage }?.let { att ->
            runCatching {
                val f = java.io.File(context.filesDir, att.localPath)
                if (f.exists()) {
                    android.graphics.BitmapFactory.decodeStream(
                        java.io.FileInputStream(f), null,
                        android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    )
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, maxOf(outWidth, outHeight) / 512)
                    }
                    android.graphics.BitmapFactory.decodeStream(java.io.FileInputStream(f), null, opts)
                } else null
            }.getOrNull()
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(64.dp)
            .entrancePop()
    ) {
        if (model != null) {
            Image(
                bitmap = model.asImageBitmap(),
                contentDescription = attachment.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(6.dp)
            ) {
                Text(
                    text = "📄",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = attachment.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    onRetryFromHere: (() -> Unit)? = null,
    onEditAndResend: (() -> Unit)? = null
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    when (message.role) {
        Message.Role.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (message.attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    ) {
                        message.attachments.take(4).forEach { attachment ->
                            MessageAttachmentTile(attachment)
                        }
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showMenu = false
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content))
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Edit & resend") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showMenu = false
                            onEditAndResend?.invoke()
                        }
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
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

        Message.Role.TOOL -> ToolMessageCard(message)

        Message.Role.ASSISTANT -> Column(
            // Subtle inset so AI text never touches the screen edges; no
            // visible border, just breathing room.
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.sm, end = Spacing.sm)
        ) {
            androidx.compose.material3.DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        showMenu = false
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content))
                    }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Retry") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        showMenu = false
                        onRetryFromHere?.invoke()
                    }
                )
            }
            Box(
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                )
            ) {
                MarkdownText(markdown = message.content)
            }
        }

        Message.Role.SYSTEM -> Unit
    }
}

/**
 * One agent tool step, rendered as a COLLAPSIBLE card: collapsed shows just
 * the tool title with a chevron; tapping expands the full output (command,
 * exit code, [err] lines…). Defaults to collapsed so a command-running chat
 * stays scannable; nothing is lost — expand to inspect.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolMessageCard(message: Message) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chevron"
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { expanded = !expanded }
            )
    ) {
        Column(
            Modifier
                .padding(Spacing.md)
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 320f))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🛠 ${message.metadata?.toolName ?: "Tool"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }
            // Animated expand/collapse — subtle, purposeful.
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }
            if (!expanded) {
                Text(
                    text = "Tap to view details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.sm, end = Spacing.sm)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 320f))
    ) {
        if (text.isEmpty()) {
            // Waiting for the first token: shimmer skeleton instead of a spinner.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SkeletonBar(Modifier.size(width = 180.dp, height = 12.dp))
                SkeletonBar(Modifier.size(width = 240.dp, height = 12.dp))
                SkeletonBar(Modifier.size(width = 120.dp, height = 12.dp))
            }
        } else {
            MarkdownText(markdown = text)
            BlinkingCaret()
        }
    }
}

/** Small blinking caret under the streaming text — “still writing” signal. */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )
    Text(
        text = "▍",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp).graphicsLayer { this.alpha = alpha }
    )
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
            // The + control is a PERMANENT part of the composer: attach, work-
            // space, terminal toggles live here on every screen state — new chat
            // included. Streaming only disables it, it never hides it.
            IconButton(onClick = onAttach) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Attach and capabilities",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Neuron…") },
                // Typing stays available during streaming — the send queues.
                enabled = true,
                minLines = 1,
                maxLines = 5,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyLarge
            )
            // Streaming: STOP first (prominent), send second — send queues the
            // next turn. Idle: send only. Same slot, animated morph.
            AnimatedContent(
                targetState = isStreaming,
                transitionSpec = {
                    (scaleIn(tween(180), initialScale = 0.6f) + fadeIn(tween(180))) togetherWith
                        (scaleOut(tween(140), targetScale = 0.6f) + fadeOut(tween(140)))
                },
                label = "composerAction"
            ) { streaming ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (streaming) {
                        IconButton(onClick = onStop) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Stop generating",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (streaming) "Queue message" else "Send",
                            tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentActivityCard(steps: List<AgentActivityUi>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.entrancePop()
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
