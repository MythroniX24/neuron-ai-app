package com.neuron.ai.core.provider

import com.neuron.ai.core.error.NeuronError
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A single message inside a conversation with an AI model.
 *
 * Kept transport-agnostic: no OpenAI/Anthropic field names leak above this layer.
 * Tool-calling fields let the agent loop represent assistant tool requests and
 * tool results without exposing wire format.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    /** Attachments referenced by this message (images are sent as vision input). */
    val attachments: List<com.neuron.ai.core.conversation.Attachment> = emptyList(),
    /** Tool calls proposed by the assistant (role ASSISTANT only). */
    val toolCalls: List<ProposedToolCall> = emptyList(),
    /** Tool-call id this message answers (role TOOL only). */
    val toolCallId: String? = null
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

/** A tool invocation requested by the model. */
data class ProposedToolCall(
    val callId: String,
    val toolId: String,
    val argumentsJson: String
)

/** Identifies a model exposed by a provider. */
data class Model(
    val id: String,
    val displayName: String,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val contextWindowTokens: Int? = null
) {
    enum class Capability { TEXT, VISION, TOOL_CALLING, STREAMING, REASONING }
}

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
    data class ToolCallRequested(
        val callId: String,
        val toolId: String,
        val argumentsJson: String
    ) : StreamEvent()

    data class Failed(val error: NeuronError) : StreamEvent()
    data object Completed : StreamEvent()
}

/** Parameters for a completion request. */
data class CompletionRequest(
    val model: Model,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double = 0.7,
    val maxOutputTokens: Int? = null
)

/** A tool exposed to the model in a request. */
data class ToolSpec(
    val id: String,
    val description: String,
    /** JSON-Schema object for the "parameters" field. */
    val parametersSchemaJson: String
)

/**
 * Abstraction over AI backends. Implementations translate this contract to the
 * concrete wire protocol; nothing above this layer may import provider specifics.
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
@Serializable
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val displayName: String,
    /** Base URL, e.g. https://api.openai.com/v1 for OpenAI-compatible endpoints. */
    val baseUrl: String,
    /** Credential lives in [com.neuron.ai.core.security.SecureCredentialStore], never here. */
    val credentialKey: String,
    val defaultModelId: String? = null,
    /** User-defined model ids; when non-empty they are used instead of /models discovery. */
    val modelIds: List<String> = emptyList(),
    /** Extra HTTP headers required by some gateways. */
    val customHeaders: Map<String, String> = emptyMap(),
    /** Whether image attachments may be sent as vision input. */
    val visionEnabled: Boolean = false,
    /** Whether the agent may offer registered tools to this provider's models. */
    val toolsEnabled: Boolean = false,
    val enabled: Boolean = true
) {
    enum class Kind { OPENAI_COMPATIBLE }
}
