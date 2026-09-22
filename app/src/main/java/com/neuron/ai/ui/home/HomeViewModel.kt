package com.neuron.ai.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.settings.SettingsRepository
import com.neuron.ai.core.conversation.ConversationRepository
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

    /** Creates a fresh conversation and reports its id via [onCreated]. */
    fun startNewChat(onCreated: (String) -> Unit) {
        if (_uiState.value.isCreatingChat) return
        viewModelScope.launch(dispatchers.io) {
            _uiState.value = _uiState.value.copy(isCreatingChat = true)
            try {
                val conversation = conversations.createConversation(
                    title = "New chat"
                )
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
