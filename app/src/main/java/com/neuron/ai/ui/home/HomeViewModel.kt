package com.neuron.ai.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.ui.chat.deriveChatTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val isCreatingChat: Boolean = false
)

class HomeViewModel(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger
) : ViewModel() {

    val themeMode = settings.themeMode

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Creates a fresh conversation and reports its id via [onCreated].
     * A non-blank [firstMessage] is persisted immediately and becomes both
     * the chat's first message and its auto-derived title — so the user's
     * first words are never swallowed by the navigation hand-off.
     */
    fun startNewChat(firstMessage: String, onCreated: (String) -> Unit) {
        if (_uiState.value.isCreatingChat) return
        viewModelScope.launch(dispatchers.io) {
            _uiState.value = _uiState.value.copy(isCreatingChat = true)
            try {
                val trimmed = firstMessage.trim()
                val conversation = conversations.createConversation(
                    title = if (trimmed.isEmpty()) "New chat" else deriveChatTitle(trimmed)
                )
                if (trimmed.isNotEmpty()) {
                    conversations.appendMessage(conversation.id, Message.Role.USER, trimmed)
                }
                // Navigation must happen on the main thread.
                withContext(dispatchers.main) { onCreated(conversation.id) }
            } catch (t: Throwable) {
                logger.e("Home", "Failed to create conversation", t)
            } finally {
                _uiState.value = _uiState.value.copy(isCreatingChat = false)
            }
        }
    }
}
