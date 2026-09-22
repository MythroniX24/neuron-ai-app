package com.neuron.ai.ui.providers

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.data.provider.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the provider editor form. */
data class ProviderFormState(
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelIdsText: String = "",
    val headersText: String = "",
    val visionEnabled: Boolean = false,
    val toolsEnabled: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: TestResult? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false
) {
    enum class TestResult { SUCCESS, FAILURE }
}

class ProvidersViewModel(
    private val providers: ProviderRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    val configs: StateFlow<List<ProviderConfig>> =
        providers.configs.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), providers.configs.value
        )

    // ---- List actions -----------------------------------------------------------

    fun delete(providerId: String) {
        viewModelScope.launch(dispatchers.io) { providers.delete(providerId) }
    }

    fun setEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) { providers.setEnabled(providerId, enabled) }
    }

    // ---- Editor -------------------------------------------------------------------

    private val _form = MutableStateFlow(ProviderFormState())
    val form: StateFlow<ProviderFormState> = _form.asStateFlow()

    /** Provider currently being edited, if any. */
    private var editingId: String? = null

    /** Fills the form when editing an existing provider. */
    fun loadForEdit(providerId: String?) {
        editingId = providerId
        if (providerId == null) return
        providers.config(providerId)?.let { config ->
            _form.value = ProviderFormState(
                name = config.displayName,
                baseUrl = config.baseUrl,
                modelIdsText = config.modelIds.joinToString(", "),
                headersText = config.customHeaders.entries
                    .joinToString("\n") { "${it.key}: ${it.value}" },
                visionEnabled = config.visionEnabled,
                toolsEnabled = config.toolsEnabled
            )
        }
    }

    fun updateForm(transform: (ProviderFormState) -> ProviderFormState) {
        _form.value = transform(_form.value)
    }

    /** Tests the connection using the current form values. */
    fun testConnection() {
        val state = _form.value
        if (state.isTesting) return
        _form.value = state.copy(isTesting = true, testResult = null)

        viewModelScope.launch(dispatchers.io) {
            val config = buildConfig(state, existingId = null)
            val result = providers.testConnection(config, state.apiKey.ifBlank { null })
            _form.value = _form.value.copy(
                isTesting = false,
                testResult = if (result.isSuccess) {
                    ProviderFormState.TestResult.SUCCESS
                } else {
                    ProviderFormState.TestResult.FAILURE
                }
            )
        }
    }

    fun save(existingId: String?) {
        val state = _form.value
        if (state.isSaving) return
        _form.value = state.copy(isSaving = true)

        viewModelScope.launch(dispatchers.io) {
            val targetId = existingId ?: editingId
            val config = buildConfig(state, targetId)
            // Blank key on edit means "keep the stored key".
            providers.save(config, state.apiKey.ifBlank { null })

            // First configured provider becomes the app default.
            if (providers.defaultProviderId.value == null) {
                val modelId = config.defaultModelId
                    ?: config.modelIds.firstOrNull()
                providers.setDefaultModel(config.id, modelId)
            }

            _form.value = _form.value.copy(isSaving = false, saved = true)
        }
    }

    private fun buildConfig(state: ProviderFormState, existingId: String?): ProviderConfig {
        val headers = state.headersText.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim() to
                    line.substring(idx + 1).trim()
            }
            .toMap()

        val id = existingId ?: providers.newConfigId()
        val credentialKey = existingId
            ?.let { providers.config(it)?.credentialKey }
            ?: providers.newCredentialKey()

        return ProviderConfig(
            id = id,
            kind = ProviderConfig.Kind.OPENAI_COMPATIBLE,
            displayName = state.name.ifBlank { "Provider" },
            baseUrl = state.baseUrl.trim(),
            credentialKey = credentialKey,
            modelIds = state.modelIdsText.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            customHeaders = headers,
            visionEnabled = state.visionEnabled,
            toolsEnabled = state.toolsEnabled,
            enabled = existingId?.let { providers.config(it)?.enabled } ?: true
        )
    }
}
