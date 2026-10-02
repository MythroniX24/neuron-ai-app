package com.neuron.ai.core.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A file attached to a message. Content lives in app-private storage;
 * only metadata is persisted in the database.
 */
@Serializable
data class Attachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Path inside app-private storage; never a raw shared-storage path. */
    val localPath: String,
    val kind: Kind
) {
    enum class Kind { TEXT, IMAGE, PDF, BINARY }

    val isImage: Boolean get() = kind == Kind.IMAGE
    val isText: Boolean get() = kind == Kind.TEXT
}

/**
 * One persisted agent-activity step (live activity timeline). Summary-only:
 * full tool output lives in the conversation's TOOL messages and is resolved
 * by [toolCallId] at display time — no duplicated storage.
 */
@Serializable
data class AgentStepRecord(
    val stepId: String,
    /** thinking | routing | tool_call | command | web_search | file_read | file_edit | build_test */
    val type: String,
    val label: String,
    val detail: String? = null,
    val toolId: String? = null,
    /** running | done | failed */
    val status: String,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long? = null
) {
    companion object {
        const val TYPE_THINKING = "thinking"
        /**
         * Milestone 6: the routing RESOLUTION — which model actually runs this
         * turn, with which capabilities, and what got degraded (e.g. tools off,
         * context capped, thermal throttle). Shown as the first timeline row so
         * the user sees WHY an on-device answer looks the way it does.
         */
        const val TYPE_ROUTING = "routing"
        const val TYPE_TOOL_CALL = "tool_call"
        /** Mid-loop narration — ongoing progress, distinct from the final answer. */
        const val TYPE_INTERMEDIATE = "intermediate"
        const val TYPE_COMMAND = "command"
        const val TYPE_WEB_SEARCH = "web_search"
        const val TYPE_FILE_READ = "file_read"
        const val TYPE_FILE_EDIT = "file_edit"
        const val TYPE_BUILD_TEST = "build_test"
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

/** Optional, transport-agnostic facts about how a message was produced. */
@Serializable
data class MessageMetadata(
    val providerId: String? = null,
    val modelId: String? = null,
    val isError: Boolean = false,
    val generationMs: Long? = null,
    /** For TOOL-role messages: the call id this message answers. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Steps of the agent turn that produced this assistant message. */
    val agentSteps: List<AgentStepRecord> = emptyList()
)

/** A chat conversation. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Provider/model this conversation is bound to (null = app default). */
    val providerId: String? = null,
    val modelId: String? = null,
    /** Pinned chats float to the top of drawer/search lists. */
    val pinned: Boolean = false,
    /** Workspace attached to this conversation (null = none). */
    val workspaceId: String? = null,
    /** Per-conversation AI Terminal capability — OFF by default. */
    val terminalEnabled: Boolean = false,
    /** Per-conversation AI Browser capability — OFF by default (Milestone 3). */
    val browserEnabled: Boolean = false
)

/** A message inside a [Conversation]. */
data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val createdAtEpochMs: Long,
    val attachments: List<Attachment> = emptyList(),
    val metadata: MessageMetadata? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
}

/** Source of truth for conversations and their messages. */
interface ConversationRepository {
    val conversations: Flow<List<Conversation>>
    fun messagesOf(conversationId: String): Flow<List<Message>>

    suspend fun getConversation(conversationId: String): Conversation?
    suspend fun createConversation(
        title: String,
        providerId: String? = null,
        modelId: String? = null
    ): Conversation

    /**
     * Creates a conversation with a caller-chosen id (unified new-chat
     * surface) — same contract as [createConversation] otherwise.
     */
    suspend fun createConversation(
        title: String,
        id: String,
        providerId: String? = null,
        modelId: String? = null
    ): Conversation

    suspend fun renameConversation(conversationId: String, title: String)
    suspend fun setConversationPinned(conversationId: String, pinned: Boolean)
    suspend fun setConversationModel(conversationId: String, providerId: String?, modelId: String?)

    /** Binds/unbinds a workspace to this conversation. */
    suspend fun setConversationWorkspace(conversationId: String, workspaceId: String?)

    /** Enables/disables the AI Terminal capability for this conversation. */
    suspend fun setConversationTerminal(conversationId: String, enabled: Boolean)

    /** Enables/disables the AI Browser capability for this conversation. */
    suspend fun setConversationBrowser(conversationId: String, enabled: Boolean)

    suspend fun appendMessage(
        conversationId: String,
        role: Message.Role,
        content: String,
        attachments: List<Attachment> = emptyList(),
        metadata: MessageMetadata? = null
    ): Message

    suspend fun updateMessage(messageId: String, content: String, metadata: MessageMetadata? = null)
    suspend fun deleteMessage(messageId: String)

    /** Deletes [messageId] and every newer message in its conversation (edit-and-resend). */
    suspend fun deleteMessagesFrom(conversationId: String, messageId: String)

    suspend fun deleteConversation(conversationId: String)

    /** Case-insensitive search over titles and message contents. */
    suspend fun searchConversations(query: String): List<Conversation>
}
