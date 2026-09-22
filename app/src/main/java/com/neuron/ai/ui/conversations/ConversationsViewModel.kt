package com.neuron.ai.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.conversation.Conversation
import com.neuron.ai.core.conversation.ConversationRepository
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val conversations: List<Conversation> = emptyList()
)

class ConversationsViewModel(
    private val repository: ConversationRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch(dispatchers.io) {
            @OptIn(FlowPreview::class)
            combine(
                repository.conversations,
                query.debounce(250)
            ) { list, q -> list to q }
                .map { (list, q) ->
                    val filtered = if (q.isBlank()) {
                        list
                    } else {
                        list.filter { conversation ->
                            conversation.title.contains(q, ignoreCase = true)
                        }
                    }
                    ConversationsUiState(isLoading = false, conversations = filtered)
                }
                .catch { _uiState.value = ConversationsUiState(isLoading = false) }
                .collect { _uiState.value = it }
        }
    }

    /** Deep search over titles + message bodies (debounced from the UI). */
    fun search(rawQuery: String) {
        val q = rawQuery.trim()
        query.value = q
        if (q.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val matches = repository.searchConversations(q)
            val current = _uiState.value.conversations
            // Merge deep matches (by message content) with title-filtered list.
            val merged = (current + matches.filter { match ->
                current.none { it.id == match.id }
            }).sortedByDescending { it.updatedAtEpochMs }
            _uiState.value = _uiState.value.copy(conversations = merged)
        }
    }

    fun rename(conversationId: String, title: String) {
        viewModelScope.launch(dispatchers.io) {
            repository.renameConversation(conversationId, title.trim())
        }
    }

    fun delete(conversationId: String) {
        viewModelScope.launch(dispatchers.io) {
            repository.deleteConversation(conversationId)
        }
    }
}
