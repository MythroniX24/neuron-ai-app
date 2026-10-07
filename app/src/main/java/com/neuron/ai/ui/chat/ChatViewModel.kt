package com.neuron.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.agent.Agent
import com.neuron.ai.core.agent.AgentEvent
import com.neuron.ai.core.agent.AgentGoal
import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.core.conversation.Conversation
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.data.provider.ProviderRepository
import com.neuron.ai.core.conversation.AgentStepRecord
import com.neuron.ai.data.local.LocalModelRouter
import com.neuron.ai.data.local.LocalRouteDecision
import com.neuron.ai.data.local.LocalRouteRequest
import com.neuron.ai.core.task.Task
import com.neuron.ai.core.task.TaskManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selectable (provider, model) pair for the top-bar model switcher. */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String
)

/** Streaming state of the current assistant turn. */
sealed class GenerationState {
    data object Idle : GenerationState()
    data class Streaming(val buffer: String) : GenerationState()
    data class Failed(
        val error: NeuronError,
        /** When the failure is a local-model load/runtime failure after backends are ready,
         *  set this so the UI can offer a one-tap jump to Settings → Debug console with the
         *  llama.cpp log already in view. Null for cloud/provider failures.
         */
        val openDiagnostics: Boolean = false
    ) : GenerationState()
}

/** One user-visible agent step rendered above the streaming text. */
data class AgentActivityUi(
    val stepId: String,
    val title: String,
    val state: AgentActivityState,
    val detail: String? = null
) {
    enum class AgentActivityState { RUNNING, DONE, FAILED }
}

/**
 * Phase 1 chat engine. Streams assistant responses through the agent core so
 * tool calls happen transparently; the user can stop, retry, regenerate and
 * edit messages. All provider access goes through [ProviderRepository].
 */
class ChatViewModel(
    private val conversationId: String,
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val tasks: com.neuron.ai.data.task.DefaultTaskManager,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val agentFactory: (AIProvider, Model, Set<String>) -> Agent,
    private val defaultModelId: String?,
    private val toolIdsProvider: suspend () -> Set<String>,
    private val importAttachmentFn: suspend (android.net.Uri) -> com.neuron.ai.core.conversation.Attachment?,
    private val importCaptureFn: suspend (java.io.File) -> com.neuron.ai.core.conversation.Attachment?,
    private val workspaces: com.neuron.ai.data.workspace.WorkspaceManagerImpl,
    private val terminalManager: com.neuron.ai.data.terminal.TerminalManager,
    /** Milestone 3: this chat's browser session is closed with the ViewModel. */
    private val browserManager: com.neuron.ai.core.integration.BrowserManager,
    /** Milestone 3: priority context builder with memory injection (no-op by default in previews/tests). */
    private val contextEngine: com.neuron.ai.ui.chat.ChatContextEngine =
        com.neuron.ai.ui.chat.ChatContextEngine(),
    /** Phase-3 shadow: the new orchestrator pipeline; null in previews/tests. */
    private val orchestrator: com.neuron.ai.context.orchestrator.ContextOrchestrator? = null,
    /** Reads attachment bytes/text so the agent can analyse user files. */
    private val attachmentStore: com.neuron.ai.data.attachment.AttachmentStore? = null,
    /** Persists the last (provider, model) pick so it survives restarts. */
    private val settings: SettingsRepository? = null,
    /**
     * Local AI (on-device inference): GGUF model registry + engine loader.
     * Null in previews/tests — every local path degrades to cloud-only.
     */
    private val localModels: com.neuron.ai.data.local.LocalModelRepository? = null,
    /** On-device provider; null when local inference is unavailable. */
    internal val localAiProvider: com.neuron.ai.data.local.LocalAiProvider? = null
) : ViewModel() {

    val messages: StateFlow<List<Message>> =
        conversations.messagesOf(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generation.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val draftAttachments: StateFlow<List<Attachment>> = _draftAttachments.asStateFlow()

    /** Message queued while a turn is streaming; auto-sent when it finishes. */
    private val _queuedMessage = MutableStateFlow<String?>(null)
    val queuedMessage: StateFlow<String?> = _queuedMessage.asStateFlow()
    private var queuedPrompt: String? = null
    private var queuedAttachments: List<Attachment> = emptyList()

    fun cancelQueuedMessage() {
        queuedPrompt = null
        queuedAttachments = emptyList()
        _queuedMessage.value = null
    }

    /** One-shot visible reason when an attachment import fails (never silent). */
    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice: StateFlow<String?> = _attachmentNotice.asStateFlow()

    fun clearAttachmentNotice() { _attachmentNotice.value = null }

    private val _activity = MutableStateFlow<List<AgentActivityUi>>(emptyList())
    val activity: StateFlow<List<AgentActivityUi>> = _activity.asStateFlow()

    /**
     * Live activity timeline (Claude-style, USE_LIVE_TIMELINE): UI-safe step
     * records streamed from the agent loop — appears the moment a step
     * starts, updates in place when it finishes, and persists onto the
     * assistant message metadata when the turn ends.
     */
    private val _timeline = MutableStateFlow<List<com.neuron.ai.core.conversation.AgentStepRecord>>(emptyList())
    val timeline: StateFlow<List<com.neuron.ai.core.conversation.AgentStepRecord>> = _timeline.asStateFlow()

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    /**
     * Set once the backing conversation for a brand-new chat is actually
     * created (on first send / first capability change). The screen uses it
     * to swap the navigation entry to the real conversation — same UI, no
     * second screen.
     */
    private val _createdConversationId = MutableStateFlow<String?>(null)
    val createdConversationId: StateFlow<String?> = _createdConversationId.asStateFlow()

    /** All (provider, model) pairs offered by enabled providers. */
    val modelOptions: StateFlow<List<ModelOption>> = providers.configs
        .map { configs ->
            configs.filter { it.enabled }.flatMap { config ->
                val ids = config.modelIds.ifEmpty { listOfNotNull(config.defaultModelId) }
                ids.map { ModelOption(config.id, config.displayName, it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null
    private var lastUserPrompt: String? = null
    private var lastUserAttachments: List<Attachment> = emptyList()

    // ---- Local AI (on-device) -------------------------------------------------

    /** Live load lifecycle for the header loading state ("Loading <model>…"). */
    val localLoadState: StateFlow<com.neuron.ai.data.local.LocalLoadState> =
        localModels?.loadState
            ?: MutableStateFlow(com.neuron.ai.data.local.LocalLoadState.Idle)

    /**
     * Switcher options: enabled cloud providers + TICKED local models —
     * downloading alone never makes a model selectable. Re-emits whenever
     * either side changes, so Settings ticks reflect immediately.
     */
    val mergedModelOptions: StateFlow<List<ModelOption>> =
        combine(
            providers.configs,
            localModels?.models
                ?: MutableStateFlow(emptyList<com.neuron.ai.data.local.LocalModelRecord>())
        ) { configs, locals ->
            val cloud = configs.filter { it.enabled }.flatMap { config ->
                val ids = config.modelIds.ifEmpty { listOfNotNull(config.defaultModelId) }
                ids.map { ModelOption(config.id, config.displayName, it) }
            }
            val local = locals.filter { it.enabledForChat }.map {
                ModelOption(LOCAL_PROVIDER_ID, "On-device", it.id)
            }
            cloud + local
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Local AI: unticking the ACTIVE model in Settings must never leave a
        // chat pointed at a disabled model — fall back to the default cloud
        // selection the moment that happens.
        localModels?.models?.let { flow ->
            viewModelScope.launch(dispatchers.io) {
                flow.collect { records ->
                    val conv = _conversation.value ?: return@collect
                    if (conv.providerId == LOCAL_PROVIDER_ID) {
                        val stillEnabled = records.any {
                            it.id == conv.modelId && it.enabledForChat
                        }
                        if (!stillEnabled) {
                            val last = settings?.lastModelSelection?.first()
                            if (last != null && providers.config(last.first)?.enabled == true) {
                                conversations.setConversationModel(
                                    conversationId, last.first, last.second
                                )
                                _conversation.value =
                                    conversations.getConversation(conversationId)
                            }
                        }
                    }
                }
            }
        }

        // Brand-new chats pre-select the user's LAST used model (persisted in
        // DataStore) — the selection survives app restarts and new chats.
        if (_conversation.value == null) {
            viewModelScope.launch(dispatchers.io) {
                val selection = settings?.lastModelSelection?.first() ?: return@launch
                if (providers.config(selection.first)?.enabled != true) return@launch
                ensureConversation()
                conversations.setConversationModel(
                    conversationId,
                    selection.first,
                    selection.second
                )
                _conversation.value = conversations.getConversation(conversationId)
            }
        }
    }

    /** Task-system mirror of the current agent turn (Milestone 1). */
    private var currentTaskId: String? = null

    fun loadConversation() {
        viewModelScope.launch(dispatchers.io) {
            _conversation.value = conversations.getConversation(conversationId)
            // A brand-new chat has no backing conversation yet — it is created
            // lazily on first send (see [ensureConversation]), never eagerly.
            if (_conversation.value != null) autoRespondIfPending()
        }
    }

    /**
     * Creates the backing conversation for a new chat using THIS screen's id,
     * so the message flow, tool environment and terminal session — all bound
     * to the same key — stay valid without any re-wiring.
     */
    private suspend fun ensureConversation() {
        if (_conversation.value != null) return
        // Carry the last-used model into the new conversation so it is
        // visible in the top bar even before the first send.
        val last = settings?.lastModelSelection?.first()
        val created = if (last != null && providers.config(last.first)?.enabled == true) {
            conversations.createConversation(
                title = "New chat",
                id = conversationId,
                providerId = last.first,
                modelId = last.second
            )
        } else {
            conversations.createConversation(title = "New chat", id = conversationId)
        }
        _conversation.value = created
        _createdConversationId.value = created.id
    }

    /**
     * A chat opened from Home carries the user's first message already
     * persisted. If the last message is still an unanswered USER message and
     * nothing is streaming, kick off the response automatically — the home
     * composer must feel like a real send, not a message that vanished.
     */
    private suspend fun autoRespondIfPending() {
        if (_generation.value !is GenerationState.Idle) return
        val last = conversations.messagesOf(conversationId).first().lastOrNull() ?: return
        if (last.role != Message.Role.USER) return
        lastUserPrompt = last.content
        lastUserAttachments = last.attachments
        runAgentTurn(last.content, currentTaskId)
    }

    // ---- Workspace & terminal capability (Milestone 2) ---------------------------

    /** Workspaces for the + → Workspace picker. */
    val workspaceList = workspaces.workspaces

    /** Terminal capability state of THIS conversation. */
    val terminalEnabled: StateFlow<Boolean> = _conversation
        .map { it?.terminalEnabled ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Browser capability state of THIS conversation (Milestone 3). */
    val browserEnabled: StateFlow<Boolean> = _conversation
        .map { it?.browserEnabled ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Creates a workspace, binds it to this chat and returns its id. */
    fun createWorkspace(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch(dispatchers.io) {
            ensureConversation()
            val ws = workspaces.create(name)
            conversations.setConversationWorkspace(conversationId, ws.id)
            _conversation.value = conversations.getConversation(conversationId)
            onCreated(ws.id)
        }
    }

    /** Binds an existing workspace to this chat. */
    fun attachWorkspace(workspaceId: String) {
        viewModelScope.launch(dispatchers.io) {
            ensureConversation()
            workspaces.open(workspaceId)
            conversations.setConversationWorkspace(conversationId, workspaceId)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

    /** Detaches the workspace from this chat (workspace itself is kept). */
    fun detachWorkspace() {
        viewModelScope.launch(dispatchers.io) {
            ensureConversation()
            conversations.setConversationWorkspace(conversationId, null)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

    /** Per-conversation Terminal toggle (+ → Terminal). */
    fun setTerminalEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            ensureConversation()
            conversations.setConversationTerminal(conversationId, enabled)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

    /** Per-conversation Browser toggle (+ → Browser) — Milestone 3. */
    fun setBrowserEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            ensureConversation()
            conversations.setConversationBrowser(conversationId, enabled)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

    /** Terminal session for the panel; bound to this conversation. */

    /** Terminal session for the panel; bound to this conversation. */
    suspend fun terminalSession(): com.neuron.ai.data.terminal.TerminalSession {
        val wsId: String? = _conversation.value?.workspaceId
        val root: java.io.File? = if (wsId != null) {
            val path: String? = workspaces.get(wsId)?.rootPath
            path?.let { java.io.File(it) }
        } else {
            null
        }
        return terminalManager.sessionFor(conversationId, wsId, root)
    }

    // ---- Model selection ------------------------------------------------------------

    /**
     * Effective provider/model: conversation override, else app default.
     * A "local" providerId resolves through the local model registry —
     * unticked/unknown local ids FALL BACK to the default cloud model so a
     * chat never points at a disabled model.
     */
    fun resolveSelection(): Pair<Any, String>? {
        val conv = _conversation.value
        val modelId = conv?.modelId
        if (conv?.providerId == LOCAL_PROVIDER_ID) {
            val local = localModels?.models?.value?.firstOrNull {
                it.id == modelId && it.enabledForChat
            }
            if (local != null) return "local" to local.id
            // Fall through to the cloud default below.
        }
        val config = providers.config(conv?.providerId) ?: providers.enabledConfig()
            ?: return null
        val resolved = modelId
            ?: config.defaultModelId
            ?: defaultModelId
            ?: config.modelIds.firstOrNull()
            ?: return null
        return config to resolved
    }

    /**
     * Builds the multi-turn context for the model via the Milestone-3 context
     * engine: prioritized sources under a character budget — recent history
     * kept whole, TOOL rows COMPRESSED (not dropped), relevant memory
     * injected as a SYSTEM block, oldest content trimmed first.
     */
        private suspend fun buildHistory(): List<ChatMessage> {
        // Model-aware budget: the selected model's estimated context window
        // drives how much history/context is assembled (Phase-1 orchestration).
        val window = resolveSelection()?.let { (providerId, modelId) ->
            if (providerId == LOCAL_PROVIDER_ID) {
                // Local models: use the GGUF-declared context length — it
                // feeds TokenBudgetManager exactly like a cloud window.
                localModels?.models?.value?.firstOrNull { it.id == modelId }
                    ?.contextLength?.toInt()
            } else {
                com.neuron.ai.data.provider.ModelCapabilities.estimateContextWindow(modelId)
            }
        }
        val engine = if (window != null) contextEngine.withWindow(window) else contextEngine
        val legacy = engine.build(conversations.messagesOf(conversationId).first())

        // ---- Phase-3 SHADOW MODE (CONTEXT_ARCHITECTURE.md §14.3) -------------
        // Run the orchestrator alongside the legacy engine and log both side
        // by side. The model still receives the LEGACY context until the new
        // pipeline is verified against real conversations — flip
        // USE_ORCHESTRATOR_CONTEXT when the comparison looks correct.
        val shadow = orchestrator?.let { pipeline ->
            runCatching {
                pipeline.buildContext(
                    query = lastUserPrompt ?: "",
                    conversationId = conversationId,
                    currentUserId = messages.value
                        .lastOrNull { it.role == Message.Role.USER }?.id,
                    workspaceId = _conversation.value?.workspaceId,
                    taskId = currentTaskId,
                    contextWindowTokens = window ?: 8_000,
                    // The current request travels via the agent goal, not the
                    // history — parity with the legacy engine's output.
                    excludeCurrentRequest = true
                )
            }.getOrNull()
        }
        if (shadow != null) {
            val report = com.neuron.ai.context.orchestrator.ContextOrchestrator
                .builtFrom(legacy, shadow)
            logger.d("ContextShadow", report.getValue("legacy"))
            logger.d("ContextShadow", report.getValue("orchestrator"))
        }
        if (USE_ORCHESTRATOR_CONTEXT && shadow != null) return shadow.messages
        return legacy
    }

    fun setModel(providerId: String, modelId: String) {
        viewModelScope.launch(dispatchers.io) {
            ensureConversation()
            conversations.setConversationModel(conversationId, providerId, modelId)
            _conversation.value = conversations.getConversation(conversationId)
            // Remember globally: survives restarts and pre-fills new chats.
            settings?.setLastModelSelection(providerId, modelId)

            // Local switch: trigger the load sequence NOW so the header shows
            // "Loading <model>…" immediately (repository re-emits state; the
            // previous local model is unloaded first inside ensureLoaded).
            if (providerId == LOCAL_PROVIDER_ID) {
                localModels?.ensureLoaded(modelId)
            }
        }
    }

    // ---- Draft attachments ------------------------------------------------------------

    fun addDraftAttachment(attachment: Attachment) {
        _draftAttachments.value = _draftAttachments.value + attachment
    }

    fun removeDraftAttachment(attachmentId: String) {
        _draftAttachments.value = _draftAttachments.value.filterNot { it.id == attachmentId }
    }

    /** Imports a picked document into private storage and adds it to the draft. */
    fun importAttachment(uri: android.net.Uri) {
        viewModelScope.launch(dispatchers.io) {
            val attachment = importAttachmentFn(uri)
            if (attachment != null) {
                _draftAttachments.value = _draftAttachments.value + attachment
                _attachmentNotice.value = null
            } else {
                logger.w("Chat", "Attachment import failed")
                _attachmentNotice.value =
                    "Could not import this file. If it is a cloud-only item, download it locally first."
            }
        }
    }

    /**
     * Imports a camera capture that has been written to [captureFile].
     * The temporary capture file is consumed (moved) into attachment storage.
     */
    fun importCameraCapture(captureFile: java.io.File) {
        viewModelScope.launch(dispatchers.io) {
            val attachment = importCaptureFn(captureFile)
            if (attachment != null) {
                _draftAttachments.value = _draftAttachments.value + attachment
                _attachmentNotice.value = null
            } else {
                logger.w("Chat", "Camera capture import failed")
                _attachmentNotice.value = "Could not save the camera capture. Try again."
            }
        }
    }

    // ---- Sending / streaming -------------------------------------------------------------

    fun send(text: String) {
        val prompt = text.trim()
        val attachments = _draftAttachments.value
        if (prompt.isEmpty() && attachments.isEmpty()) return
        if (_generation.value is GenerationState.Streaming) {
            // Never drop what the user typed mid-turn — queue it; it is sent
            // automatically when the running turn finishes.
            queuedPrompt = prompt
            queuedAttachments = attachments
            _queuedMessage.value = prompt.ifBlank { "${attachments.size} attachment(s)" }
            _draftAttachments.value = emptyList()
            return
        }
        startTurn(prompt, attachments)
    }

    private fun startTurn(prompt: String, attachments: List<Attachment>) {
        lastUserAttachments = attachments

        job = viewModelScope.launch(dispatchers.io) {
            // Text-like attachments are flattened into the prompt as 【FILE】
            // blocks so any model (vision or not) can actually read the file
            // content. Bounded: ≤3 files, ≤4k chars each.
            val effectivePrompt = prompt + flattenTextAttachments(attachments)
            lastUserPrompt = effectivePrompt

            ensureConversation()
            conversations.appendMessage(
                conversationId,
                Message.Role.USER,
                prompt,
                attachments = attachments
            )
            _draftAttachments.value = emptyList()
            maybeAutoTitle(prompt)
            val task = tasks.create(
                title = prompt.take(60).ifBlank { "Agent turn" },
                conversationId = conversationId
            )
            currentTaskId = task.id
            runAgentTurn(effectivePrompt, task.id, attachments = attachments)
            drainQueuedTurn()
        }
    }

    /** Sends a turn queued during streaming, if any (no-op otherwise). */
    private fun drainQueuedTurn() {
        val next = queuedPrompt ?: return
        if (_generation.value is GenerationState.Failed) return
        queuedPrompt = null
        val nextAttachments = queuedAttachments
        queuedAttachments = emptyList()
        _queuedMessage.value = null
        startTurn(next, nextAttachments)
    }

    /**
     * Flattens text-like attachments into the prompt as fenced 【FILE】 blocks
     * so the model can read their content. Bounded: ≤3 files, ≤4k chars each;
     * read failures degrade to a note instead of failing the whole turn.
     */
    private suspend fun flattenTextAttachments(attachments: List<Attachment>): String {
        val store = attachmentStore ?: return ""
        val textFiles = attachments.filter { it.isText }.take(3)
        if (textFiles.isEmpty()) return ""
        val builder = StringBuilder()
        textFiles.forEach { att ->
            val content = runCatching { store.extractText(att, maxBytes = 4_000) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "[could not read file content]"
            builder.append("\n\n【FILE: ").append(att.displayName).append("】\n")
                .append(content)
                .append("\n【END FILE ").append(att.displayName).append("】")
        }
        return builder.toString()
    }

    /**
     * Names the chat with an AI-generated 2-3 word title from the first user
     * message, like mainstream chat apps. Fires once, in the background;
     * falls back to the offline heuristic; never blocks or fails the send.
     */
    private suspend fun maybeAutoTitle(prompt: String) {
        val conversation = _conversation.value ?: return
        val isUntitled = conversation.title == "New chat" || conversation.title.isBlank()
        if (!isUntitled || prompt.isBlank()) return

        val selection = resolveSelection()
        val title = if (selection != null && selection.first != LOCAL_PROVIDER_ID) {
            @Suppress("UNCHECKED_CAST")
            val config = selection.first as ProviderConfig
            val provider = providers.provider(config.id)
            if (provider != null) {
                AiNaming.generate(prompt, provider, selection.second)
            } else {
                null
            }
        } else {
            null
        } ?: deriveChatTitle(prompt)

        runCatching { conversations.renameConversation(conversationId, title) }
            .onSuccess {
                // Keep the local state in sync — the top bar and drawer read it.
                _conversation.value = conversation.copy(title = title)
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Task CANCELLED status is set inside the turn's cancellation handler,
        // where the scope is already cancelled — safe there via NonCancellable.
    }    fun retry() {
        val prompt = lastUserPrompt ?: return
        if (_generation.value is GenerationState.Streaming) return

        cancelQueuedMessage()
        // The user message is already persisted; only re-run the answer.
        job = viewModelScope.launch(dispatchers.io) {
            runAgentTurn(prompt, currentTaskId, attachments = lastUserAttachments ?: emptyList())
        }
    }

    fun regenerate() {
        if (_generation.value is GenerationState.Streaming) return
        viewModelScope.launch(dispatchers.io) {
            val lastUser = messages.value.lastOrNull { it.role == Message.Role.USER } ?: return@launch
            // Remove the trailing assistant answer before re-running.
            messages.value.reversed().takeWhile {
                it.role == Message.Role.ASSISTANT
            }.forEach { conversations.deleteMessage(it.id) }

            lastUserAttachments = lastUser.attachments
            // Re-flatten text files — the persisted row keeps only the raw prompt.
            val effective = lastUser.content + flattenTextAttachments(lastUser.attachments)
            lastUserPrompt = effective
            runAgentTurn(effective, currentTaskId, attachments = lastUser.attachments)
            drainQueuedTurn()
        }
    }

    /**
     * Regenerates from a specific ASSISTANT message: deletes it and every
     * later row, then re-runs the turn against the previous user message.
     */
    fun retryFrom(messageId: String) {
        if (_generation.value is GenerationState.Streaming) return
        viewModelScope.launch(dispatchers.io) {
            val all = conversations.messagesOf(conversationId).first()
            val index = all.indexOfFirst { it.id == messageId }
            if (index <= 0) return@launch
            val target = all[index]
            if (target.role != Message.Role.ASSISTANT) return@launch

            // Drop the answer itself and everything after it.
            all.drop(index).forEach { conversations.deleteMessage(it.id) }

            val userMessage = all.subList(0, index).lastOrNull { it.role == Message.Role.USER }
                ?: return@launch
            lastUserAttachments = userMessage.attachments
            val effective = userMessage.content + flattenTextAttachments(userMessage.attachments)
            lastUserPrompt = effective
            runAgentTurn(effective, currentTaskId, attachments = userMessage.attachments)
            drainQueuedTurn()
        }
    }

    fun editAndResend(messageId: String, newContent: String) {
        if (_generation.value is GenerationState.Streaming) return
        viewModelScope.launch(dispatchers.io) {
            val original = messages.value.firstOrNull { it.id == messageId }
            conversations.deleteMessagesFrom(conversationId, messageId)
            conversations.appendMessage(
                conversationId,
                Message.Role.USER,
                newContent,
                attachments = original?.attachments ?: emptyList()
            )
            val originalAttachments = original?.attachments ?: emptyList()
            lastUserAttachments = originalAttachments
            val effective = newContent + flattenTextAttachments(originalAttachments)
            lastUserPrompt = effective
            runAgentTurn(effective, currentTaskId, attachments = originalAttachments)
        }
    }

    fun clearError() {
        if (_generation.value is GenerationState.Failed) {
            _generation.value = GenerationState.Idle
            _activity.value = emptyList()
        }
    }

    // ---- Core turn ------------------------------------------------------------------------

    /** Runs one agent turn; the user message must already be persisted.
     *  [taskId] mirrors this turn onto the task system when provided. */
    private suspend fun runAgentTurn(
        prompt: String,
        taskId: String?,
        attachments: List<Attachment> = emptyList()
    ) {
        val selection = resolveSelection()
        if (selection == null) {
            taskId?.let { tid ->
                tasks.updateStatus(tid, Task.Status.FAILED)
                tasks.reportError(tid, "No model selected")
            }
            _generation.value = GenerationState.Failed(
                NeuronError.Provider(
                    "No model selected. Tap the model name in the top bar to pick one, " +
                        "or edit your provider and use \"Load models\"."
                )
            )
            return
        }        // Local AI: route through the SAME agent loop with the on-device
        // provider — no parallel inference path, no special-cased runtime.
        if (selection.first == LOCAL_PROVIDER_ID) {
            val localProvider = localAiProvider
            if (localProvider == null) {
                _generation.value = GenerationState.Failed(
                    NeuronError.Provider("Local models are unavailable on this device.")
                )
                return
            }
            // Milestone 6: CAPABILITY-AWARE ROUTING. The selected GGUF's own
            // metadata (chat template, architecture, declared context) decides
            // what this turn may use; anything it cannot serve is refused or
            // degraded VISIBLY instead of silently.
            // Milestone 9: the user's per-model rules get the first say about
            // WHICH local model runs this kind of turn.
            val turnKind = when {
                lastUserAttachments.any { it.isImage } ->
                    com.neuron.ai.data.local.LocalTurnKind.VISION
                turnWantsTools() -> com.neuron.ai.data.local.LocalTurnKind.TOOLS
                else -> com.neuron.ai.data.local.LocalTurnKind.TEXT
            }
            val routedId = localModels?.pickRoutedModel(turnKind)?.id ?: selection.second
            val record = localModels?.models?.value?.firstOrNull { it.id == routedId }
            val route = record?.let { model ->
                LocalModelRouter.decide(
                    model,
                    LocalRouteRequest(
                        hasImageAttachments = lastUserAttachments.any { it.isImage },
                        wantsTools = turnWantsTools()
                    ),
                    // Milestone 9: images only route on-device when a real
                    // mmproj projector sits beside the GGUF.
                    hasProjector = localModels?.hasVisionProjector(model) == true
                )
            }
            if (route is LocalRouteDecision.Refuse) {
                taskId?.let { tid ->
                    tasks.updateStatus(tid, Task.Status.FAILED)
                    tasks.reportError(tid, route.reason)
                }
                _generation.value = GenerationState.Failed(NeuronError.Provider(route.reason))
                return
            }
            val routed = route as? LocalRouteDecision.Route
            val capabilities = routed?.capabilities
            val routeStep = if (USE_LIVE_TIMELINE && routed != null) {
                buildRouteStep(routed.capabilities, routed.degraded)
            } else {
                null
            }
            runAgentTurnWith(
                provider = localProvider,
                modelId = routedId,
                configId = LOCAL_PROVIDER_ID,
                // Capability-honest: whatever the GGUF declares, nothing more.
                visionEnabled = capabilities?.supportsVision ?: false,
                toolsEnabled = capabilities?.supportsTools ?: false,
                prompt = prompt,
                taskId = taskId,
                attachments = attachments,
                routeStep = routeStep,
                contextWindowTokens = capabilities?.contextWindowTokens,
                // When the local engine could not load/run on this device AFTER
                // backends were ready, the error banner offers a one-tap jump to
                // Settings → Debug console where the llama.cpp log is visible.
                openDiagnosticsOnFailure = true
            )
            return
        }

        @Suppress("UNCHECKED_CAST")
        val config = selection.first as ProviderConfig
        val modelId = selection.second

        val provider = providers.provider(config.id) ?: run {
            taskId?.let { tid ->
                tasks.updateStatus(tid, Task.Status.FAILED)
                tasks.reportError(tid, "Provider disabled")
            }
            _generation.value = GenerationState.Failed(
                NeuronError.Provider("Provider \"${config.displayName}\" is disabled.")
            )
            return
        }

        runAgentTurnWith(
            provider = provider,
            modelId = modelId,
            configId = config.id,
            visionEnabled = config.visionEnabled,
            toolsEnabled = config.toolsEnabled,
            prompt = prompt,
            taskId = taskId,
            attachments = attachments
        )
    }

    /**
     * Shared turn body for cloud AND local providers — one agent loop, one
     * tool execution path, one context pipeline. The provider instance is
     * the ONLY difference; there is no parallel local inference path.
     *
     * When [openDiagnosticsOnFailure] is true and the turn fails because the
     * local engine could not load or run on this device AFTER backends were
     * ready, the resulting error banner offers a one-tap jump to Settings →
     * Debug console where the llama.cpp log is visible.
     */
    private suspend fun runAgentTurnWith(
        provider: AIProvider,
        modelId: String,
        configId: String,
        visionEnabled: Boolean,
        toolsEnabled: Boolean,
        prompt: String,
        taskId: String?,
        attachments: List<Attachment>,
        routeStep: AgentStepRecord? = null,
        /** True context window when the provider DECLARES one (on-device GGUF). */
        contextWindowTokens: Int? = null,
        /** Local-only: when true, surface the Debug console from the error banner. */
        openDiagnosticsOnFailure: Boolean = false
    ) {
        // Capability-honest Model for this turn: declared flags + a declared
        // window when the provider has one (cloud estimates from the id,
        // on-device from the GGUF metadata — never a guess).
        val capabilityModel = Model(
            id = modelId,
            displayName = modelId,
            supportsVision = visionEnabled,
            supportsTools = toolsEnabled,
            contextWindowTokens = contextWindowTokens
                ?: com.neuron.ai.data.provider.ModelCapabilities.estimateContextWindow(modelId)
        )
        // Milestone 3 multimodal gate: reject inputs the model cannot accept
        // BEFORE hitting the provider — never send unsupported input blindly.
        val capabilityError = com.neuron.ai.data.provider.ModelCapabilities.validateInput(
            model = capabilityModel,
            attachments = lastUserAttachments
        )
        if (capabilityError != null) {
            taskId?.let { tid ->
                tasks.updateStatus(tid, Task.Status.FAILED)
                tasks.reportError(tid, capabilityError)
            }
            _generation.value = GenerationState.Failed(NeuronError.Provider(capabilityError))
            return
        }

        // Mirror the turn onto the task system.
        taskId?.let { tasks.updateStatus(it, Task.Status.RUNNING) }

        // The user's message is already in the conversation history.
        _activity.value = emptyList()
        _timeline.value = emptyList()
        // The routing resolution is the FIRST timeline row: which model runs,
        // with what capabilities, and what was degraded (incl. live thermal
        // throttle). It persists with the answer, so the transcript explains
        // itself later.
        routeStep?.let { step ->
            if (USE_LIVE_TIMELINE) _timeline.value = listOf(step)
        }
        _generation.value = GenerationState.Streaming("")

        val started = System.currentTimeMillis()
        var assistantBuffer = StringBuilder()
        var streamError: NeuronError? = null

        val agent = agentFactory(
            provider,
            capabilityModel,
            if (toolsEnabled) toolIdsProvider() else emptySet()
        )

        try {
            agent.run(
                AgentGoal(
                    instruction = prompt,
                    conversationId = conversationId,
                    attachments = attachments,
                    history = buildHistory()
                )
            ).collect { event ->
                    when (event) {
                        is AgentEvent.ActivityStarted -> {
                            if (USE_LIVE_TIMELINE) {
                                val record = com.neuron.ai.core.agent.AgentStepMapper
                                    .toRecord(event.activity)
                                _timeline.value = _timeline.value
                                    .filterNot { it.stepId == record.stepId } + record
                            }
                            _activity.value = _activity.value + AgentActivityUi(
                                stepId = event.activity.stepId,
                                title = event.activity.title,
                                state = AgentActivityUi.AgentActivityState.RUNNING,
                                detail = event.activity.detail
                            )
                        }

                        is AgentEvent.ActivityUpdated -> {
                            if (USE_LIVE_TIMELINE) {
                                val record = com.neuron.ai.core.agent.AgentStepMapper
                                    .toRecord(event.activity)
                                _timeline.value = _timeline.value
                                    .filterNot { it.stepId == record.stepId } + record
                            }
                            taskId?.let { tid ->
                                tasks.reportActivity(
                                    tid,
                                    (if (event.activity.state == com.neuron.ai.core.agent.AgentActivity.State.FAILED) "✗ " else "✓ ") +
                                        event.activity.title
                                )
                                if (event.activity.state == com.neuron.ai.core.agent.AgentActivity.State.DONE) {
                                    val current = _activity.value.size
                                    tasks.reportProgress(tid, current, null)
                                }
                            }
                            _activity.value = _activity.value.map { item ->
                                if (item.stepId == event.activity.stepId) {
                                    item.copy(
                                        state = when (event.activity.state) {
                                            com.neuron.ai.core.agent.AgentActivity.State.DONE ->
                                                AgentActivityUi.AgentActivityState.DONE
                                            com.neuron.ai.core.agent.AgentActivity.State.FAILED ->
                                                AgentActivityUi.AgentActivityState.FAILED
                                            else -> AgentActivityUi.AgentActivityState.RUNNING
                                        },
                                        detail = event.activity.detail
                                    )
                                } else {
                                    item
                                }
                            }
                            // Completed tool steps become visible TOOL-role messages.
                            val isToolStep = !event.activity.stepId.startsWith("think-")
                            val finished =
                                event.activity.state == com.neuron.ai.core.agent.AgentActivity.State.DONE ||
                                    event.activity.state == com.neuron.ai.core.agent.AgentActivity.State.FAILED
                            if (isToolStep && finished && event.activity.detail != null) {
                                conversations.appendMessage(
                                    conversationId,
                                    Message.Role.TOOL,
                                    event.activity.detail.take(8_000),
                                    metadata = MessageMetadata(
                                        toolCallId = event.activity.stepId,
                                        toolName = event.activity.title
                                    )
                                )
                            }
                        }

                        is AgentEvent.TextDelta -> {
                            assistantBuffer.append(event.text)
                            _generation.value = GenerationState.Streaming(assistantBuffer.toString())
                        }

                        is AgentEvent.IntermediateMessage -> {
                            // Mid-loop narration is ONGOING progress — persist
                            // it as its own ASSISTANT message so the transcript
                            // reads IN SEQUENCE: narration → tool card →
                            // narration → tool card → final answer. It also
                            // lands in the live timeline as a step. It is never
                            // merged into the final answer bubble.
                            if (USE_LIVE_TIMELINE) {
                                _timeline.value = _timeline.value +
                                    com.neuron.ai.core.agent.AgentStepMapper.toRecord(
                                        com.neuron.ai.core.agent.AgentActivity(
                                            stepId = "narrate-${event.text.hashCode()}",
                                            title = event.text.take(120),
                                            state = com.neuron.ai.core.agent.AgentActivity.State.DONE,
                                            toolId = null,
                                            startedAtEpochMs = System.currentTimeMillis()
                                        ),
                                        typeOverride =
                                            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_INTERMEDIATE
                                    )
                            }
                            runCatching {
                                conversations.appendMessage(
                                    conversationId,
                                    Message.Role.ASSISTANT,
                                    event.text.trim(),
                                    metadata = MessageMetadata(
                                        providerId = configId,
                                        modelId = modelId,
                                        isError = false
                                    )
                                )
                            }
                            // The streaming bubble must not carry narration
                            // forward: each turn's text stands alone.
                            assistantBuffer = StringBuilder()
                            _generation.value = GenerationState.Streaming("")
                        }

                        is AgentEvent.TextBuffer -> {
                            // Agent repaired the stream (e.g. a tool call was
                            // recovered from plain-text markup) — show the
                            // cleaned buffer, never the raw markup.
                            assistantBuffer = StringBuilder(event.text)
                            _generation.value = GenerationState.Streaming(event.text)
                        }

                        is AgentEvent.Finished -> {
                            // Never shrink an already-streamed buffer: a
                            // transient empty model turn or a stray Finished
                            // must not erase partial output the user saw.
                            if (event.summary.length > assistantBuffer.length) {
                                assistantBuffer = StringBuilder(event.summary)
                            }
                            _generation.value = GenerationState.Streaming(assistantBuffer.toString())
                            // The answer has arrived: hide the live progress
                            // cards NOW. The (filtered) steps already persist
                            // on the assistant message metadata and render
                            // attached to it — leaving the live card visible
                            // rendered a stuck "Understanding request /
                            // Running tool" card AND a duplicate after the
                            // response ended.
                            _activity.value = emptyList()
                            _timeline.value = emptyList()
                        }

                        is AgentEvent.Failed -> {
                            streamError = NeuronError.Provider(event.message)
                        }

                        // Permission pauses mirror onto the task system so the
                        // Tasks screen shows the true state.
                        is AgentEvent.PermissionRequested -> {
                            taskId?.let { tasks.updateStatus(it, Task.Status.WAITING_FOR_PERMISSION) }
                        }
                        is AgentEvent.PermissionResolved -> {
                            taskId?.let { tasks.updateStatus(it, Task.Status.RUNNING) }
                        }
                    }
                }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // User stopped generation; persist partial text + CANCELLED task
            // inside NonCancellable — the scope is already cancelled here.
            withContext(kotlinx.coroutines.NonCancellable) {
                taskId?.let { tasks.updateStatus(it, Task.Status.CANCELLED) }
                if (assistantBuffer.isNotEmpty()) {
                    conversations.appendMessage(
                        conversationId,
                        Message.Role.ASSISTANT,
                        assistantBuffer.toString(),
                        metadata = MessageMetadata(
                            providerId = configId,
                            modelId = modelId,
                            generationMs = System.currentTimeMillis() - started,
                            agentSteps = cancelStepsSnapshot()
                        )
                    )
                }
            }
            _generation.value = GenerationState.Idle
            _activity.value = emptyList()
            throw cancelled
        }

        val elapsed = System.currentTimeMillis() - started

        if (streamError != null) {
            _generation.value = GenerationState.Failed(
                streamError!!,
                openDiagnostics = openDiagnosticsOnFailure
            )
        } else if (assistantBuffer.isNotBlank()) {
            conversations.appendMessage(
                conversationId,
                Message.Role.ASSISTANT,
                assistantBuffer.toString(),
                metadata = MessageMetadata(
                    providerId = configId,
                    modelId = modelId,
                    generationMs = elapsed,
                    agentSteps = persistableSteps()
                )
            )
        } else {
            _generation.value = GenerationState.Failed(
                NeuronError.Provider("The model returned an empty response."),
                openDiagnostics = openDiagnosticsOnFailure
            )
        }

        _activity.value = emptyList()
        // Keep the finished timeline visible until the next turn begins.
        if (_generation.value is GenerationState.Streaming) {
            _generation.value = GenerationState.Idle
        }

        // Drain a message queued while this turn was streaming — but never
        // auto-send over an unacknowledged failure.
        if (_generation.value !is GenerationState.Failed) {
            val next = queuedPrompt
            if (next != null) {
                queuedPrompt = null
                val nextAttachments = queuedAttachments
                queuedAttachments = emptyList()
                _queuedMessage.value = null
                startTurn(next, nextAttachments)
            }
        }
    }

    /**
     * ViewModel cleared (chat closed / process finishing): stop a live agent
     * turn so no generation continues for a chat nobody is viewing, and mark
     * the mirrored task CANCELLED — the UI must never claim activity after
     * its owner is gone.
     */
    override fun onCleared() {
        if (_generation.value is GenerationState.Streaming || _generation.value is GenerationState.Failed) {
            job?.cancel()
        }
        // No WebView leaks: the chat's browser session dies with its ViewModel.
        runCatching { browserManager.closeSession(conversationId) }
        currentTaskId?.let { id ->
            viewModelScope.launch(dispatchers.io) {
                val task = tasks.tasks.first().find { it.id == id }
                if (task != null && !task.isFinished) {
                    tasks.updateStatus(id, com.neuron.ai.core.task.Task.Status.CANCELLED)
                }
            }
        }
        super.onCleared()
    }

    /**
     * Milestone 6: does THIS turn actually want tools? Only when the chat has
     * a capability bound to it (Terminal / Browser / Workspace) — a plain chat
     * never needs them, so we do not degrade the model for nothing.
     */
    private fun turnWantsTools(): Boolean {
        val conv = _conversation.value ?: return false
        return conv.terminalEnabled || conv.browserEnabled || conv.workspaceId != null
    }

    /**
     * The routing row: which model runs this turn, with which capabilities,
     * and everything that got degraded on the way (missing tools, capped
     * context, or the live thermal/battery throttle from milestone 7).
     */
    private fun buildRouteStep(
        capabilities: com.neuron.ai.data.local.LocalModelCapabilities,
        degraded: List<String>
    ): AgentStepRecord {
        val now = System.currentTimeMillis()
        return AgentStepRecord(
            stepId = "route-local",
            type = AgentStepRecord.TYPE_ROUTING,
            label = LocalModelRouter.timelineLabel(capabilities),
            detail = LocalModelRouter.timelineDetail(
                capabilities = capabilities,
                degraded = degraded,
                throttleNote = localModels?.throttleNotice()
            ),
            toolId = null,
            status = AgentStepRecord.STATUS_DONE,
            startedAtEpochMs = now,
            finishedAtEpochMs = now
        )
    }

    /**
     * Persisted timeline hygiene: generic bookkeeping rows ("Understanding
     * request", "Continuing", "Thinking") are loop machinery, not work —
     * they only matter LIVE while the user waits. On the persisted card they
     * read as noise after the response, so only meaningful steps survive:
     * tool calls, narration, and failure analysis.
     */
    private fun persistableSteps(): List<com.neuron.ai.core.conversation.AgentStepRecord> {
        if (!USE_LIVE_TIMELINE) return emptyList()
        return _timeline.value.filterNot { step ->
            step.type == com.neuron.ai.core.conversation.AgentStepRecord.TYPE_THINKING &&
                GENERIC_THINKING_LABELS.any { step.label.startsWith(it) }
        }
    }

    /**
     * Cancel/failure path: freeze the timeline snapshot and mark any step
     * still RUNNING as failed so the persisted timeline reflects reality —
     * cancelled work must not render as completed work.
     */
    private fun cancelStepsSnapshot(): List<com.neuron.ai.core.conversation.AgentStepRecord> {
        if (!USE_LIVE_TIMELINE) return emptyList()
        return persistableSteps().map { step ->
            if (step.status == com.neuron.ai.core.conversation.AgentStepRecord.STATUS_RUNNING) {
                step.copy(
                    status = com.neuron.ai.core.conversation.AgentStepRecord.STATUS_FAILED,
                    finishedAtEpochMs = step.finishedAtEpochMs
                        ?: System.currentTimeMillis()
                )
            } else {
                step
            }
        }
    }

    companion object {
        /** providerId under which ALL local models surface in the selector. */
        const val LOCAL_PROVIDER_ID = "local"

        /**
         * Phase-3 shadow rollout switch (CONTEXT_ARCHITECTURE.md §14.3):
         * false = orchestrator runs in shadow only (logged, never returned);
         * flip to true after the logged comparison verifies the new pipeline.
         */
        private const val USE_ORCHESTRATOR_CONTEXT = false

        /**
         * Live agent activity timeline (Claude-style) — flag-gated rollout:
         * flip after real multi-step runs (search + terminal + coding) verify
         * the UI stays lightweight and the summaries stay accurate.
         */
        private const val USE_LIVE_TIMELINE = true

        /** Loop-machinery labels hidden from the PERSISTED timeline. */
        private val GENERIC_THINKING_LABELS = listOf(
            "Understanding request", "Continuing", "Thinking"
        )
    }
}
