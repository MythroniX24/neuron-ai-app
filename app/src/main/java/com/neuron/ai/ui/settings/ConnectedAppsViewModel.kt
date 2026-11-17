package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.google.GoogleAccount
import com.neuron.ai.core.integration.google.GoogleConnectorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connected Apps state: which Google accounts are linked, connection progress
 * and the last message worth showing the user. All Google credentials stay on
 * the connector backend — this screen only manages the account list.
 */
class ConnectedAppsViewModel(
    private val repository: GoogleConnectorRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    val accounts: StateFlow<List<GoogleAccount>> = repository.accounts

    /** True when this build carries a connector backend URL. */
    val configured: Boolean = repository.configured

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Links an account using the Google server auth code from the consent flow. */
    fun connect(serverAuthCode: String) {
        if (serverAuthCode.isBlank()) {
            _message.value = "Google did not return an authorization code. Try again."
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            _message.value = null
            val result = repository.connect(serverAuthCode)
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { "Connected ${it.label}." },
                onFailure = { it.message ?: "Google connection failed." }
            )
        }
    }

    fun disconnect(accountId: String) {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            val result = repository.disconnect(accountId)
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { "Google account disconnected." },
                onFailure = { it.message ?: "Could not disconnect the account." }
            )
        }
    }

    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            repository.syncAccounts().onFailure {
                _message.value = it.message ?: "Could not refresh the account list."
            }
            _busy.value = false
        }
    }

    /** Surfaces a local failure (sign-in cancelled, consent could not start). */
    fun report(text: String) {
        _message.value = text
    }
}
