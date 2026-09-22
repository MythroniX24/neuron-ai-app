package com.neuron.ai.ui.providers

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.data.provider.ProviderPresets
import com.neuron.ai.data.provider.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the provider editor form. */
data class ProviderFormState(
    val presetId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelIdsText: String = "",
    val headersText: String = "",
    val visionEnabled: Boolean = false,
    val toolsEnabled: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: TestResult? = null,
    val testMessage: String? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    // ---- Model loading (/models) -------------------------------------------
    val isLoadingModels: Boolean = false,
    val discoveredModels: List<String> = emptyList(),
    val selectedModels: Set<String> = emptySet(),
    val modelsError: String? = null
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

    /** Fills the form from a preset (new provider) or stored config (edit). */
    fun applyPreset(presetId: String) {
        val preset = ProviderPresets.byId(presetId) ?: return
        editingId = null
        _form.value = ProviderFormState(
            presetId = presetId,
            name = if (presetId == "custom") "" else preset.displayName,
            baseUrl = preset.baseUrl
        )
    }

    /** Fills the form when editing an existing provider. */
    fun loadForEdit(providerId: String?) {
        editingId = providerId
        if (providerId == null) {
            if (_form.value.presetId == null && _form.value.name.isBlank()) {
                _form.value = ProviderFormState(presetId = "custom")
            }
            return
        }
        providers.config(providerId)?.let { config ->
            _form.value = ProviderFormState(
                presetId = null,
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

    fun toggleModel(modelId: String) {
        val state = _form.value
        val next = if (modelId in state.selectedModels) {
            state.selectedModels - modelId
        } else {
            state.selectedModels + modelId
        }
        _form.value = state.copy(selectedModels = next)
    }

    /**
     * Live /models lookup using the base URL + API key currently in the form.
     * Results become a tappable checklist; picks sync into the Model IDs field.
     */
    fun loadModels() {
        val state = _form.value
        if (state.isLoadingModels || state.baseUrl.isBlank()) return
        _form.value = state.copy(isLoadingModels = true, modelsError = null)

        viewModelScope.launch(dispatchers.io) {
            val probeConfig = ProviderConfig(
                id = "probe",
                kind = ProviderConfig.Kind.OPENAI_COMPATIBLE,
                displayName = "probe",
                baseUrl = state.baseUrl.trim(),
                credentialKey = "probe-unused",
                modelIds = emptyList(),
                customHeaders = parseHeaders(state.headersText)
            )
            val provider = com.neuron.ai.data.provider.OpenAICompatibleProvider(
                id = "probe",
                config = probeConfig,
                credentials = com.neuron.ai.core.security.InMemorySecureCredentialStore().apply {
                    state.apiKey.takeIf { it.isNotBlank() }?.let { put("probe-unused", it) }
                }
            )
            val result = runCatching { provider.listModels() }
            _form.value = _form.value.copy(
                isLoadingModels = false,
                discoveredModels = result.getOrDefault(emptyList())
                    .map { it.id }
                    .distinct(),
                modelsError = result.exceptionOrNull()?.let { error ->
                    (error as? com.neuron.ai.data.provider.ProviderException)?.error?.message
                        ?: "Could not load models. Check the base URL and API key."
                }
            )
        }
    }

    /** Tests the connection using the current form values. */
    fun testConnection() {
        val state = _form.value
        if (state.isTesting) return
        _form.value = state.copy(isTesting = true, testResult = null, testMessage = null)

        viewModelScope.launch(dispatchers.io) {
            val config = buildConfig(state, existingId = null)
            val result = providers.testConnection(config, state.apiKey.ifBlank { null })
            _form.value = _form.value.copy(
                isTesting = false,
                testResult = if (result.isSuccess) {
                    ProviderFormState.TestResult.SUCCESS
                } else {
                    ProviderFormState.TestResult.FAILURE
                },
                testMessage = result.exceptionOrNull()?.let { error ->
                    (error as? com.neuron.ai.data.provider.ProviderException)?.error?.message
                        ?: "Connection failed."
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

    private fun parseHeaders(headersText: String): Map<String, String> =
        headersText.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

    private fun buildConfig(state: ProviderFormState, existingId: String?): ProviderConfig {
        // Selected models from the loader take precedence over the free-text field.
        val selectedText = state.selectedModels.sorted().joinToString(", ")
        val modelText = if (state.selectedModels.isNotEmpty()) selectedText else state.modelIdsText

        return ProviderConfig(
            id = existingId ?: providers.newConfigId(),
            kind = ProviderConfig.Kind.OPENAI_COMPATIBLE,
            displayName = state.name.ifBlank { "Provider" },
            baseUrl = state.baseUrl.trim(),
            credentialKey = existingId
                ?.let { providers.config(it)?.credentialKey }
                ?: providers.newCredentialKey(),
            modelIds = modelText.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            customHeaders = parseHeaders(state.headersText),
            visionEnabled = state.visionEnabled,
            toolsEnabled = state.toolsEnabled,
            enabled = existingId?.let { providers.config(it)?.enabled } ?: true
        )
    }
}
