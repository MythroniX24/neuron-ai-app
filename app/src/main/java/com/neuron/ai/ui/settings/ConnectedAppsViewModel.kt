package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.google.GoogleAccount
import com.neuron.ai.core.integration.google.GoogleConnectorRepository
import com.neuron.ai.core.integration.google.GoogleService
import com.neuron.ai.data.google.BackendHealth
import com.neuron.ai.data.google.GoogleConnectorDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connected Apps state: which Google accounts are linked, which services each
 * one can use, connection progress, the last message worth showing and the
 * diagnostics behind a failed connect. All Google credentials stay on the
 * connector backend — this screen only manages the account list.
 */
class ConnectedAppsViewModel(
    private val repository: GoogleConnectorRepository,
    private val diagnostics: GoogleConnectorDiagnostics,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    val accounts: StateFlow<List<GoogleAccount>> = repository.accounts

    /** True when this build carries a connector backend URL. */
    val configured: Boolean = repository.configured

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Which service's connect button is spinning, if any. */
    private val _busyService = MutableStateFlow<GoogleService?>(null)
    val busyService: StateFlow<GoogleService?> = _busyService.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private val _health = MutableStateFlow<BackendHealth?>(null)
    val health: StateFlow<BackendHealth?> = _health.asStateFlow()

    /** Scopes already granted by any linked account. */
    fun grantedScopes(): List<String> = accounts.value.flatMap { it.scopes }.distinct()

    /** Current read-only diagnostics snapshot (rebuilds on every call). */
    fun diagnosticsSnapshot(): GoogleConnectorDiagnostics.Snapshot = diagnostics.snapshot()

    fun markStarting(service: GoogleService) {
        _busy.value = true
        _busyService.value = service
        _message.value = null
    }

    fun clearBusy() {
        _busy.value = false
        _busyService.value = null
    }

    /** Links an account using the Google server auth code from the consent flow. */
    fun connect(serverAuthCode: String, service: GoogleService? = null) {
        if (serverAuthCode.isBlank()) {
            _messageIsError.value = true
            _message.value = "Google did not return an authorization code. Try again."
            clearBusy()
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            _message.value = null
            val result = repository.connect(serverAuthCode)
            _busy.value = false
            _busyService.value = null
            _messageIsError.value = result.isFailure
            _message.value = result.fold(
                onSuccess = { account ->
                    val what = service?.label?.let { "$it connected" } ?: "Connected"
                    "$what — ${account.label}."
                },
                onFailure = { it.message ?: "Google connection failed." }
            )
        }
    }

    fun disconnect(accountId: String) {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            val result = repository.disconnect(accountId)
            _busy.value = false
            _messageIsError.value = result.isFailure
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
                _messageIsError.value = true
                _message.value = it.message ?: "Could not refresh the account list."
            }
            _busy.value = false
        }
    }

    /** Surfaces a local failure (sign-in cancelled, consent could not start). */
    fun report(text: String, isError: Boolean = true) {
        _messageIsError.value = isError
        _message.value = text
        clearBusy()
    }

    /** Live probe of the connector Worker, shown in the diagnostics card. */
    fun checkBackend() {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            _health.value = diagnostics.checkBackend()
            _busy.value = false
        }
    }

    /** Copyable plain-text report of everything that explains a failure. */
    fun diagnosticsText(): String {
        val snapshot = diagnosticsSnapshot()
        return buildString {
            appendLine("Neuron-AI connector diagnostics")
            appendLine(snapshot.build.report().trimEnd())
            appendLine("configured: ${snapshot.configured}")
            appendLine("web client: ${snapshot.webClientId}")
            appendLine("backend: ${snapshot.backendUrl}")
            appendLine("last sign-in error: ${snapshot.lastSignInError ?: "none"}")
            _health.value?.let { health ->
                appendLine("backend probe: ${health.status} in ${health.latencyMs}ms — ${health.detail}")
            }
            appendLine("accounts:")
            if (snapshot.accounts.isEmpty()) {
                appendLine("  (none)")
            } else {
                snapshot.accounts.forEach { account ->
                    val services = account.services.joinToString(", ") { it.label }.ifBlank { "no service scope" }
                    appendLine("  ${account.email ?: account.id} — $services")
                    appendLine("    scopes: ${account.scopes.size}")
                }
            }
        }
    }
}
