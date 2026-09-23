package com.neuron.ai.ui.chat

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.data.conversation.RoomConversationRepository
import com.neuron.ai.data.tool.WorkspaceToolEnv
import com.neuron.ai.data.workspace.WorkspaceContext
import com.neuron.ai.data.workspace.WorkspaceManagerImpl

/**
 * Conversation-scoped [WorkspaceToolEnv]: resolves the conversation's
 * attached workspace and Terminal capability at execution time. Sessions and
 * capability state never leak across conversations — every lookup is keyed
 * by THIS conversation id.
 */
class ConversationToolEnv(
    private val conversationId: String,
    private val conversations: RoomConversationRepository,
    private val workspaces: WorkspaceManagerImpl,
    private val dispatchers: DispatcherProvider
) : WorkspaceToolEnv {

    override suspend fun activeWorkspace(): WorkspaceContext? {
        val wsId = conversations.getConversation(conversationId)?.workspaceId ?: return null
        val workspace = workspaces.get(wsId) ?: return null
        return WorkspaceContext(workspace, dispatchers)
    }

    override suspend fun terminalEnabled(): Boolean =
        conversations.getConversation(conversationId)?.terminalEnabled ?: false

    override suspend fun terminalSessionKey(): String = conversationId
}
