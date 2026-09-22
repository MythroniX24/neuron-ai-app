package com.neuron.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Chat state for Phase 0: messages persist through the repository seam.
 * There is deliberately no mock AI reply — assistant streaming arrives with
 * real providers in Phase 1.
 */
class ChatViewModel(
    private val conversationId: String,
    private val conversations: ConversationRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger
) : ViewModel() {

    val messages: StateFlow<List<Message>> =
        conversations.messagesOf(conversationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            try {
                conversations.appendMessage(
                    conversationId = conversationId,
                    role = Message.Role.USER,
                    content = trimmed
                )
            } catch (t: Throwable) {
                logger.e("Chat", "Failed to append message", t)
            }
        }
    }
}
