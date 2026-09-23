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
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.data.provider.ProviderRepository
import com.neuron.ai.core.task.Task
import com.neuron.ai.core.task.TaskManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Failed(val error: NeuronError) : GenerationState()
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
    private val terminalManager: com.neuron.ai.data.terminal.TerminalManager
) : ViewModel() {

    val messages: StateFlow<List<Message>> =
        conversations.messagesOf(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generation.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val draftAttachments: StateFlow<List<Attachment>> = _draftAttachments.asStateFlow()

    private val _activity = MutableStateFlow<List<AgentActivityUi>>(emptyList())
    val activity: StateFlow<List<AgentActivityUi>> = _activity.asStateFlow()

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

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

    /** Task-system mirror of the current agent turn (Milestone 1). */
    private var currentTaskId: String? = null

    fun loadConversation() {
        viewModelScope.launch(dispatchers.io) {
            _conversation.value = conversations.getConversation(conversationId)
            autoRespondIfPending()
        }
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

    /** Creates a workspace, binds it to this chat and returns its id. */
    fun createWorkspace(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch(dispatchers.io) {
            val ws = workspaces.create(name)
            conversations.setConversationWorkspace(conversationId, ws.id)
            _conversation.value = conversations.getConversation(conversationId)
            onCreated(ws.id)
        }
    }

    /** Binds an existing workspace to this chat. */
    fun attachWorkspace(workspaceId: String) {
        viewModelScope.launch(dispatchers.io) {
            workspaces.open(workspaceId)
            conversations.setConversationWorkspace(conversationId, workspaceId)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

    /** Detaches the workspace from this chat (workspace itself is kept). */
    fun detachWorkspace() {
        viewModelScope.launch(dispatchers.io) {
            conversations.setConversationWorkspace(conversationId, null)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

    /** Per-conversation Terminal toggle (+ → Terminal). */
    fun setTerminalEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            conversations.setConversationTerminal(conversationId, enabled)
            _conversation.value = conversations.getConversation(conversationId)
        }
    }

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

    /** Effective provider/model: conversation override, else app default. */
    fun resolveSelection(): Pair<ProviderConfig, String>? {
        val conv = _conversation.value
        val config = providers.config(conv?.providerId) ?: providers.enabledConfig() ?: return null
        val modelId = conv?.modelId
            ?: config.defaultModelId
            ?: defaultModelId
            ?: config.modelIds.firstOrNull()
            ?: return null
        return config to modelId
    }

    /**
     * Builds the multi-turn context for the model: everything before the
     * current user message, oldest first. TOOL rows are skipped — their
     * assistant tool-call parents are not persisted, and dangling tool rows
     * are rejected by several providers. Capped to keep requests sane.
     */
    private suspend fun buildHistory(): List<ChatMessage> =
        buildChatContext(conversations.messagesOf(conversationId).first())

    fun setModel(providerId: String, modelId: String) {
        viewModelScope.launch(dispatchers.io) {
            conversations.setConversationModel(conversationId, providerId, modelId)
            _conversation.value = conversations.getConversation(conversationId)
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
            } else {
                logger.w("Chat", "Attachment import failed")
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
            } else {
                logger.w("Chat", "Camera capture import failed")
            }
        }
    }

    // ---- Sending / streaming -------------------------------------------------------------

    fun send(text: String) {
        val prompt = text.trim()
        val attachments = _draftAttachments.value
        if (prompt.isEmpty() && attachments.isEmpty()) return
        if (_generation.value is GenerationState.Streaming) return

        lastUserPrompt = prompt
        lastUserAttachments = attachments

        job = viewModelScope.launch(dispatchers.io) {
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
            runAgentTurn(prompt, task.id)
        }
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
        val title = if (selection != null) {
            val (config, modelId) = selection
            val provider = providers.provider(config.id)
            if (provider != null) {
                AiNaming.generate(prompt, provider, modelId)
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
    }

    fun retry() {
        val prompt = lastUserPrompt ?: return
        if (_generation.value is GenerationState.Streaming) return
        // The user message is already persisted; only re-run the answer.
        job = viewModelScope.launch(dispatchers.io) { runAgentTurn(prompt, currentTaskId) }
    }

    fun regenerate() {
        if (_generation.value is GenerationState.Streaming) return
        viewModelScope.launch(dispatchers.io) {
            val lastUser = messages.value.lastOrNull { it.role == Message.Role.USER } ?: return@launch
            // Remove the trailing assistant answer before re-running.
            messages.value.reversed().takeWhile {
                it.role == Message.Role.ASSISTANT
            }.forEach { conversations.deleteMessage(it.id) }

            lastUserPrompt = lastUser.content
            lastUserAttachments = lastUser.attachments
            runAgentTurn(lastUser.content, currentTaskId)
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
            lastUserPrompt = newContent
            lastUserAttachments = original?.attachments ?: emptyList()
            runAgentTurn(newContent, currentTaskId)
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
    private suspend fun runAgentTurn(prompt: String, taskId: String?) {
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
        }
        val (config, modelId) = selection
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

        // Mirror the turn onto the task system.
        taskId?.let { tasks.updateStatus(it, Task.Status.RUNNING) }

        // The user's message is already in the conversation history.
        _activity.value = emptyList()
        _generation.value = GenerationState.Streaming("")

        val started = System.currentTimeMillis()
        var assistantBuffer = StringBuilder()
        var streamError: NeuronError? = null

        val agent = agentFactory(
            provider,
            Model(id = modelId, displayName = modelId),
            if (config.toolsEnabled) toolIdsProvider() else emptySet()
        )

        try {
            agent.run(
                AgentGoal(
                    instruction = prompt,
                    conversationId = conversationId,
                    history = buildHistory()
                )
            ).collect { event ->
                    when (event) {
                        is AgentEvent.ActivityStarted -> {
                            _activity.value = _activity.value + AgentActivityUi(
                                stepId = event.activity.stepId,
                                title = event.activity.title,
                                state = AgentActivityUi.AgentActivityState.RUNNING,
                                detail = event.activity.detail
                            )
                        }

                        is AgentEvent.ActivityUpdated -> {
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

                        is AgentEvent.Finished -> {
                            assistantBuffer = StringBuilder(event.summary)
                            _generation.value = GenerationState.Streaming(assistantBuffer.toString())
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
                            providerId = config.id,
                            modelId = modelId,
                            generationMs = System.currentTimeMillis() - started
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
            _generation.value = GenerationState.Failed(streamError!!)
        } else if (assistantBuffer.isNotBlank()) {
            conversations.appendMessage(
                conversationId,
                Message.Role.ASSISTANT,
                assistantBuffer.toString(),
                metadata = MessageMetadata(
                    providerId = config.id,
                    modelId = modelId,
                    generationMs = elapsed
                )
            )
        } else {
            _generation.value = GenerationState.Failed(
                NeuronError.Provider("The model returned an empty response.")
            )
        }

        _activity.value = emptyList()
        if (_generation.value is GenerationState.Streaming) {
            _generation.value = GenerationState.Idle
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
        currentTaskId?.let { id ->
            viewModelScope.launch(dispatchers.io) {
                val task = kotlinx.coroutines.flow.first(tasks.tasks).find { it.id == id }
                if (task != null && !task.isFinished) {
                    tasks.updateStatus(id, com.neuron.ai.core.task.Task.Status.CANCELLED)
                }
            }
        }
        super.onCleared()
    }
}
