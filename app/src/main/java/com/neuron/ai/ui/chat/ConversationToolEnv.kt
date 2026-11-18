package com.neuron.ai.ui.chat

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.workspace.Workspace
import com.neuron.ai.data.conversation.RoomConversationRepository
import com.neuron.ai.data.tool.WorkspaceToolEnv
import com.neuron.ai.data.workspace.WorkspaceContext
import com.neuron.ai.data.workspace.WorkspaceManagerImpl
import java.io.File

/**
 * Conversation-scoped [WorkspaceToolEnv]: resolves the conversation's
 * attached workspace and Terminal/Browser capabilities at execution time.
 * Sessions and capability state never leak across conversations — every
 * lookup is keyed by THIS conversation id.
 *
 * With no workspace attached (the normal case — the + panel no longer asks
 * for one) the file tools get the WHOLE device storage, so "read my
 * Downloads folder" or "edit that file" works like it would on a computer.
 * [deviceRoot] returns null while the user has not granted "All files access",
 * which the tools report as such.
 */
class ConversationToolEnv(
    private val conversationId: String,
    private val conversations: RoomConversationRepository,
    private val workspaces: WorkspaceManagerImpl,
    private val dispatchers: DispatcherProvider,
    private val deviceRoot: () -> File? = { null }
) : WorkspaceToolEnv {

    override suspend fun activeWorkspace(): WorkspaceContext? {
        val wsId = conversations.getConversation(conversationId)?.workspaceId
        if (wsId != null) {
            workspaces.get(wsId)?.let { return WorkspaceContext(it, dispatchers) }
        }
        val root = deviceRoot() ?: return null
        return WorkspaceContext(
            Workspace(
                id = DEVICE_WORKSPACE_ID,
                name = "Device storage",
                rootPath = root.canonicalPath,
                createdAtEpochMs = 0L,
                lastOpenedAtEpochMs = 0L
            ),
            dispatchers
        )
    }

    override suspend fun terminalEnabled(): Boolean =
        conversations.getConversation(conversationId)?.terminalEnabled ?: false

    override suspend fun browserEnabled(): Boolean =
        conversations.getConversation(conversationId)?.browserEnabled ?: true

    override suspend fun terminalSessionKey(): String = conversationId

    companion object {
        const val DEVICE_WORKSPACE_ID = "device"
    }
}
