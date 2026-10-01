package com.neuron.ai.data.local

import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.Completion
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * The on-device model as JUST ANOTHER AiProvider — it plugs into the model
 * selector, the agent loop and TokenBudgetManager with zero special-casing.
 *
 * Capability-honest by design: local models declare supportsTools=false and
 * supportsVision=false so capability-aware routing warns/routes instead of
 * silently degrading. Generation is greedy-decoded streaming text; tool
 * markup the model prints as plain text is recovered upstream by the agent
 * loop's existing TextToolCallParser transport-repair path — no parallel
 * tool plumbing here.
 */
class LocalAiProvider(
    private val repository: LocalModelRepository
) : AIProvider {

    override val id = "local"
    override val displayName = "On-device"

    override suspend fun listModels(): List<Model> =
        repository.models.value.filter { it.enabledForChat }.map { record ->
            Model(
                id = record.id,
                displayName = record.displayName,
                // Honest defaults: most local models will NOT do native
                // tool-calling or vision reliably.
                supportsTools = false,
                supportsVision = false,
                contextWindowTokens = record.contextLength?.toInt()?.coerceAtMost(4096)
            )
        }

    override suspend fun complete(request: CompletionRequest): Completion {
        val buffer = StringBuilder()
        var tokens = 0
        streamInternal(request) { piece ->
            buffer.append(piece)
            tokens++
        }
        return Completion(
            message = ChatMessage(ChatMessage.Role.ASSISTANT, buffer.toString()),
            modelId = request.model.id,
            outputTokens = tokens
        )
    }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = channelFlow {
        val record = repository.models.value.firstOrNull { it.id == request.model.id }
        if (record == null) {
            send(
                StreamEvent.Failed(
                    NeuronError.Provider("Local model \"${request.model.id}\" is not registered.")
                )
            )
            return@channelFlow
        }

        // Ensure loaded — switching models mid-chat unloads the previous one.
        val loadResult = repository.ensureLoaded(record.id)
        if (loadResult.isFailure) {
            send(
                StreamEvent.Failed(
                    NeuronError.Provider(
                        "Could not load \"${record.displayName}\": " +
                            (loadResult.exceptionOrNull()?.message ?: "unknown error")
                    )
                )
            )
            return@channelFlow
        }

        // Render the conversation through the model's own chat template so
        // instruct-tuned models see the shape they were trained on.
        val prompt = renderPrompt(request.messages)
        val maxTokens = (request.maxOutputTokens ?: 1024).coerceAtMost(2048)

        // The JNI generate call BLOCKS its thread and delivers tokens through
        // a callback — run it in a child coroutine and bridge the tokens into
        // the channel. The channel closes when this scope (incl. the child)
        // completes.
        launch {
            val result = LocalEngineLoader.generateStreaming(prompt, maxTokens) { piece ->
                trySend(StreamEvent.Delta(piece))
            }
            when (result) {
                is LocalEngineLoader.GenerationResult.Error ->
                    send(StreamEvent.Failed(NeuronError.Provider(result.message)))
                is LocalEngineLoader.GenerationResult.Done -> send(StreamEvent.Completed)
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Minimal ChatML-style renderer. When the loaded model exposes its own
     * chat template, a future iteration can switch on it; ChatML is the
     * broadest instruct format and degrades gracefully.
     */
    private fun renderPrompt(messages: List<ChatMessage>): String {
        val builder = StringBuilder()
        for (message in messages) {
            when (message.role) {
                ChatMessage.Role.SYSTEM -> builder.append("<|im_start|>system\n")
                    .append(message.content).append("<|im_end|>\n")
                ChatMessage.Role.USER -> builder.append("<|im_start|>user\n")
                    .append(message.content).append("<|im_end|>\n")
                ChatMessage.Role.ASSISTANT -> builder.append("<|im_start|>assistant\n")
                    .append(message.content)
                    .append("<|im_end|>\n")
                ChatMessage.Role.TOOL -> builder.append("<|im_start|>user\n")
                    .append("[tool result] ").append(message.content).append("<|im_end|>\n")
            }
        }
        builder.append("<|im_start|>assistant\n")
        return builder.toString()
    }

    /** Synchronous generation helper used by [complete]. */
    private fun streamInternal(request: CompletionRequest, onPiece: (String) -> Unit) {
        val prompt = renderPrompt(request.messages)
        val maxTokens = (request.maxOutputTokens ?: 1024).coerceAtMost(2048)
        when (val result = LocalEngineLoader.generateStreaming(prompt, maxTokens, onPiece)) {
            is LocalEngineLoader.GenerationResult.Error ->
                throw IllegalStateException(result.message)
            is LocalEngineLoader.GenerationResult.Done -> Unit
        }
    }
}
