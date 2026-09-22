package com.neuron.ai.core.provider

import com.neuron.ai.core.error.NeuronError
import kotlinx.coroutines.flow.Flow

/**
 * A single message inside a conversation with an AI model.
 *
 * Kept transport-agnostic: no OpenAI/Anthropic field names leak above this layer.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    /** Names of tool outputs or attachments referenced by this message. */
    val attachments: List<String> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

/** Identifies a model exposed by a provider. */
data class Model(
    val id: String,
    val displayName: String,
    val supportsTools: Boolean = false,
    val contextWindowTokens: Int? = null
)

/** Non-streaming completion result. */
data class Completion(
    val message: ChatMessage,
    val modelId: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)

/** A chunk of a streaming completion. */
sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data class ToolCallRequested(val toolId: String, val argumentsJson: String) : StreamEvent()
    data class Failed(val error: NeuronError) : StreamEvent()
    data object Completed : StreamEvent()
}

/** Parameters for a completion request. */
data class CompletionRequest(
    val model: Model,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxOutputTokens: Int? = null
)

/**
 * Abstraction over AI backends. Phase 0 ships the registry and configuration
 * seams; concrete OpenAI-compatible transports arrive in Phase 1.
 */
interface AIProvider {
    val id: String
    val displayName: String

    /** Lists models this provider currently exposes for the stored credential. */
    suspend fun listModels(): List<Model>

    /** One-shot completion. */
    suspend fun complete(request: CompletionRequest): Completion

    /** Streaming completion as a cold flow of events. */
    fun stream(request: CompletionRequest): Flow<StreamEvent>
}

/** User-configured connection to a provider endpoint. */
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val displayName: String,
    /** Base URL, e.g. https://api.openai.com/v1 for OpenAI-compatible endpoints. */
    val baseUrl: String,
    /** Credential lives in [com.neuron.ai.core.security.SecureCredentialStore], never here. */
    val credentialKey: String,
    val defaultModelId: String? = null
) {
    enum class Kind { OPENAI_COMPATIBLE }
}
