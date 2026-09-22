package com.neuron.ai.data.provider

import android.content.Context
import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.core.security.SecureCredentialStore
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Owns user-configured providers: persistence (JSON file in app-private
 * storage), instantiation, and connection testing. Provider instances are
 * cached per config revision so header/key edits take effect immediately.
 */
class ProviderRepository(
    context: Context,
    private val credentials: SecureCredentialStore,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val io = dispatchers.io
    private val json = Json { ignoreUnknownKeys = true }
    private val file = java.io.File(context.filesDir, "providers.json")

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val providers = linkedMapOf<String, OpenAICompatibleProvider>()

    val defaultModelId = MutableStateFlow<String?>(null)
    val defaultProviderId = MutableStateFlow<String?>(null)

    init {
        load()
    }

    // ---- CRUD ------------------------------------------------------------------

    suspend fun save(config: ProviderConfig, apiKey: String?): ProviderConfig = withContext(io) {
        val withKey = if (!apiKey.isNullOrBlank()) {
            credentials.put(config.credentialKey, apiKey)
            config
        } else {
            config
        }
        _configs.value = _configs.value.filterNot { it.id == withKey.id } + withKey
        persist()
        invalidate(withKey.id)
        withKey
    }

    suspend fun delete(providerId: String) = withContext(io) {
        _configs.value.find { it.id == providerId }?.let { config ->
            credentials.remove(config.credentialKey)
        }
        _configs.value = _configs.value.filterNot { it.id == providerId }
        persist()
        invalidate(providerId)
        if (defaultProviderId.value == providerId) defaultProviderId.value = null
        Unit
    }

    suspend fun setEnabled(providerId: String, enabled: Boolean) = withContext(io) {
        _configs.value = _configs.value.map {
            if (it.id == providerId) it.copy(enabled = enabled) else it
        }
        persist()
        invalidate(providerId)
    }

    suspend fun setDefaultModel(providerId: String?, modelId: String?) = withContext(io) {
        defaultProviderId.value = providerId
        defaultModelId.value = modelId
        persist()
    }

    fun newConfigId(): String = "prov-" + UUID.randomUUID().toString().take(8)

    fun newCredentialKey(): String = "credential-" + UUID.randomUUID().toString()

    fun config(providerId: String?): ProviderConfig? =
        _configs.value.firstOrNull { it.id == providerId }

    /** First enabled provider, or null. */
    fun enabledConfig(): ProviderConfig? =
        _configs.value.firstOrNull { it.enabled }

    /** Returns a live provider for an enabled config, or null. */
    fun provider(providerId: String?): AIProvider? {
        val config = config(providerId) ?: enabledConfig() ?: return null
        if (!config.enabled) return null
        return providers.getOrPut(config.id) { instantiate(config) }
    }

    // ---- Connection testing -------------------------------------------------------

    /**
     * Tries to list models; returns them or a user-presentable error.
     * A candidate API key is tested under a throwaway credential key so the
     * stored key of an existing provider is never touched or left behind.
     */
    suspend fun testConnection(config: ProviderConfig, apiKey: String?): Result<List<Model>> =
        withContext(io) {
            val testConfig = if (!apiKey.isNullOrBlank()) {
                config.copy(credentialKey = config.credentialKey + "-probe")
                    .also { credentials.put(it.credentialKey, apiKey) }
            } else {
                config
            }
            try {
                Result.success(instantiate(testConfig).listModels())
            } catch (e: ProviderException) {
                Result.failure(e)
            } catch (t: Throwable) {
                logger.w("Provider", "Connection test failed", t)
                Result.failure(t)
            } finally {
                if (testConfig.credentialKey != config.credentialKey) {
                    credentials.remove(testConfig.credentialKey)
                }
            }
        }

    // ---- Internals ------------------------------------------------------------------

    private fun instantiate(config: ProviderConfig): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            id = config.id,
            config = config,
            credentials = credentials
        )

    private fun invalidate(providerId: String) {
        providers.remove(providerId)
    }

    private fun load() {
        runCatching {
            if (file.exists()) {
                val raw = file.readText()
                if (raw.isNotBlank()) {
                    _configs.value = json.decodeFromString<List<ProviderConfig>>(raw)
                }
            }
        }.onFailure { logger.w("Provider", "Could not load provider configs", it) }
    }

    private fun persist() {
        runCatching {
            file.writeText(json.encodeToString<List<ProviderConfig>>(_configs.value))
        }.onFailure { logger.w("Provider", "Could not save provider configs", it) }
    }
}
