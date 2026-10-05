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
 * Capability-honest by design (milestone 6): capabilities are read from the
 * GGUF's own metadata — its chat template decides tool support, its
 * architecture decides vision, its declared context length is capped for
 * phones — so capability-aware routing surfaces every degradation instead of
 * silently guessing. Generation is greedy-decoded streaming text; tool
 * markup the model prints as plain text is recovered upstream by the agent
 * loop's existing TextToolCallParser transport-repair path — no parallel
 * tool plumbing here.
 */
class LocalAiProvider(
    private val repository: LocalModelRepository,
    /**
     * Milestone 9: reads an attachment's raw bytes for the vision projector.
     * Injected (rather than reaching for a store) so the provider stays
     * testable; null = image input unavailable.
     */
    private val readImageBytes: (suspend (String) -> ByteArray?)? = null
) : AIProvider {

    override val id = "local"
    override val displayName = "On-device"

    override suspend fun listModels(): List<Model> =
        repository.models.value.filter { it.enabledForChat }.map { record ->
            // Milestone 6: capabilities come from the GGUF itself (chat
            // template + architecture + declared context), not from a guess.
            // Milestone 9: vision additionally requires a real mmproj file.
            val capabilities = LocalModelRouter.capabilities(
                record,
                hasProjector = repository.hasVisionProjector(record)
            )
            Model(
                id = capabilities.modelId,
                displayName = capabilities.displayName,
                supportsTools = capabilities.supportsTools,
                supportsVision = capabilities.supportsVision,
                contextWindowTokens = capabilities.contextWindowTokens
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
        // instruct-tuned models see the shape they were trained on. With an
        // image and a live projector, the media marker goes where the picture
        // belongs so mtmd can splice in the embeddings.
        val image = latestImageAttachment(request.messages)
        val imageBytes = image?.let { attachment ->
            readImageBytes?.invoke(attachment.id)
        }
        // Milestone 7: a hot/low-battery device generates fewer tokens per
        // turn, so an answer never runs the phone into a thermal wall.
        val maxTokens = (request.maxOutputTokens ?: 1024)
            .coerceAtMost(2048)
            .coerceAtMost(repository.maxOutputTokens())

        // A 4k-token window and an unbounded transcript do not mix: without
        // this the chat breaks permanently once it outgrows the context.
        val fitted = ContextTrimmer.fit(
            turns = buildTurns(request.messages, imageMessageHasMarker = imageBytes != null),
            contextTokens = request.model.contextWindowTokens
                ?: LocalModelRouter.effectiveContextTokens(record.contextLength),
            maxOutputTokens = maxTokens,
            render = { turns, addAssistant ->
                LocalEngineLoader.renderWithChatTemplate(turns, addAssistant)
            },
            countTokens = { prompt -> LocalEngineLoader.countTokens(prompt) }
        )
        val prompt = fitted.prompt
        // If the picture's own turn was trimmed away, fall back to text: mtmd
        // is handed one bitmap against a prompt with no marker and rejects it.
        val useVision = imageBytes != null && fitted.hasMediaMarker

        // The JNI generate call BLOCKS its thread and delivers tokens through
        // a callback — run it in a child coroutine and bridge the tokens into
        // the channel. The channel closes when this scope (incl. the child)
        // completes.
        launch {
            val result = if (useVision) {
                LocalEngineLoader.generateMultimodalStreaming(
                    prompt, imageBytes!!, maxTokens
                ) { piece -> trySend(StreamEvent.Delta(piece)) }
            } else {
                LocalEngineLoader.generateStreaming(prompt, maxTokens) { piece ->
                    trySend(StreamEvent.Delta(piece))
                }
            }
            when (result) {
                is LocalEngineLoader.GenerationResult.Error ->
                    send(StreamEvent.Failed(NeuronError.Provider(result.message)))
                is LocalEngineLoader.GenerationResult.Done -> send(StreamEvent.Completed)
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * The most recent image attachment in the conversation — the one the
     * projector will actually be fed. Only the LAST one is passed: mtmd needs
     * exactly one marker per bitmap, and one picture is the common case.
     */
    private fun latestImageAttachment(messages: List<ChatMessage>) =
        messages.lastOrNull { message -> message.attachments.any { it.isImage } }
            ?.attachments?.firstOrNull { it.isImage }

    /**
     * Renders the conversation with the MODEL'S OWN chat template, falling back
     * to ChatML (see [ChatTemplateRenderer]). Hand-writing a ChatML-shaped
     * prompt here, as this used to, fed Llama-3 / Mistral / Gemma checkpoints a
     * prompt shape they were never trained on: no control tokens at all, so the
     * model answered the system prompt or kept talking past its turn.
     *
     * [imageMessageHasMarker] puts the media marker INSIDE the last
     * image-bearing turn - exactly one marker, matching exactly one bitmap
     * handed to mtmd, in the position where the picture belongs.
     */
    private fun buildTurns(
        messages: List<ChatMessage>,
        imageMessageHasMarker: Boolean = false
    ): List<ChatTemplateRenderer.Turn> {
        val imageMessageIndex = if (imageMessageHasMarker) {
            messages.indexOfLast { message -> message.attachments.any { it.isImage } }
        } else {
            -1
        }
        val turns = messages.mapIndexed { index, message ->
            val role = when (message.role) {
                ChatMessage.Role.SYSTEM -> ChatTemplateRenderer.Turn.SYSTEM
                ChatMessage.Role.ASSISTANT -> ChatTemplateRenderer.Turn.ASSISTANT
                // Tool results ride as a user turn: no built-in chat template
                // accepts a "tool" role, and asking for one makes every
                // renderer reject the WHOLE conversation.
                ChatMessage.Role.USER, ChatMessage.Role.TOOL ->
                    ChatTemplateRenderer.Turn.USER
            }
            val content = if (index == imageMessageIndex) {
                ChatTemplateRenderer.withMediaMarker(message.content)
            } else {
                message.content
            }
            ChatTemplateRenderer.Turn(role, content)
        }
        return turns
    }

    /** Blocking generation helper used by [complete]. */
    private suspend fun streamInternal(
        request: CompletionRequest,
        onPiece: (String) -> Unit
    ) {
        // complete() bypassed the stream() path, which meant it could run with
        // nothing loaded and report "No model is loaded" instead of loading.
        val record = repository.models.value.firstOrNull { it.id == request.model.id }
            ?: throw IllegalStateException(
                "Local model \"${request.model.id}\" is not registered."
            )
        repository.ensureLoaded(record.id).getOrThrow()
        val maxTokens = (request.maxOutputTokens ?: 1024)
            .coerceAtMost(2048)
            .coerceAtMost(repository.maxOutputTokens())
        val contextTokens = request.model.contextWindowTokens
            ?: LocalModelRouter.effectiveContextTokens(record.contextLength)
            ?: 0
        val prompt = ContextTrimmer.fit(
            turns = buildTurns(request.messages, imageMessageHasMarker = false),
            contextTokens = contextTokens,
            maxOutputTokens = maxTokens,
            render = { turns, addAssistant ->
                LocalEngineLoader.renderWithChatTemplate(turns, addAssistant)
            },
            countTokens = { text -> LocalEngineLoader.countTokens(text) }
        ).prompt
        when (val result = LocalEngineLoader.generateStreaming(prompt, maxTokens, onPiece)) {
            is LocalEngineLoader.GenerationResult.Error ->
                throw IllegalStateException(result.message)
            is LocalEngineLoader.GenerationResult.Done -> Unit
        }
    }
}
