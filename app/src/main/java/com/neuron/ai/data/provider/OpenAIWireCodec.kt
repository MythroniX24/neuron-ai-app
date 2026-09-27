package com.neuron.ai.data.provider

import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.CompletionRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Wire codec between Neuron's transport-agnostic [ChatMessage] model and the
 * OpenAI-compatible JSON protocol. Stateless and pure Kotlin — unit-testable
 * without Android. Streaming state lives in [SseChunkParser] (one per stream).
 */
internal class OpenAIWireCodec(private val json: Json) {

    /**
     * Encodes a completion request. [imageData] maps attachment ids to already
     * loaded PNG/JPEG bytes; image attachments without an entry are skipped.
     * [toolsEnabled] = false omits the function-calling schema entirely — many
     * models/providers reject it outright, and a tool-less turn must still
     * complete (the agent loop falls back to plain chat in that case).
     */
    fun encodeRequest(
        request: CompletionRequest,
        imageData: Map<String, ByteArray> = emptyMap(),
        toolsEnabled: Boolean = true
    ): JsonObject = buildJsonObject {
        put("model", request.model.id)
        put(
            "messages",
            buildJsonArray {
                // The safety net also needs to know whether TOOL rows are
                // legal on THIS request: when no tools are wired (schema
                // rejection fallback, tool-less models), tool rows and
                // tool_calls are converted to plain text instead — strict
                // gateways reject tool messages without a tools array.
                val toolsWired = request.tools.isNotEmpty() && toolsEnabled
                repairToolSequence(request.messages, toolsWired)
                    .forEach { add(encodeMessage(it, imageData)) }
            }
        )
        if (request.tools.isNotEmpty() && toolsEnabled) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.id)
                            put("description", tool.description)
                            put("parameters", json.parseToJsonElement(tool.parametersSchemaJson))
                        })
                    })
                }
            })
        }
        put("temperature", request.temperature)
        put("stream", true)
        request.maxOutputTokens?.let { put("max_tokens", it) }
    }

    internal fun encodeMessage(
        message: ChatMessage,
        imageData: Map<String, ByteArray> = emptyMap()
    ): JsonObject = buildJsonObject {
        put("role", message.role.name.lowercase())
        when (message.role) {
            ChatMessage.Role.TOOL -> {
                // Never emit an empty/null TOOL content — strict validators
                // require a non-empty string here.
                put("content", message.content.ifBlank { "[no output]" })
                message.toolCallId?.let { put("tool_call_id", it) }
            }

            ChatMessage.Role.ASSISTANT ->
                if (message.toolCalls.isNotEmpty()) {
                    // Empty STRING, never null: several OpenAI-compatible
                    // servers (vLLM/llama.cpp-style) reject "content": null
                    // on assistant messages outright.
                    put("content", message.content.ifBlank { "" })
                    put("tool_calls", buildJsonArray {
                        message.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.callId)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", call.toolId)
                                    put("arguments", call.argumentsJson)
                                })
                            })
                        }
                    })
                } else {
                    put("content", message.content)
                }

            ChatMessage.Role.USER -> {
                val images = message.attachments
                    .filter { it.isImage }
                    .mapNotNull { att -> imageData[att.id]?.let { att to it } }

                if (images.isEmpty()) {
                    put("content", message.content)
                } else {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", message.content)
                        })
                        images.forEach { (att, bytes) ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", toDataUrl(att, bytes)) })
                            })
                        }
                    })
                }
            }

            ChatMessage.Role.SYSTEM -> put("content", message.content)
        }
    }

    /**
     * Tool-sequence safety net (OpenAI protocol): a TOOL message is only
     * valid when the previous message is an ASSISTANT carrying a matching
     * tool_call id. The agent loop always emits valid sequences, but rebuilt
     * histories (persisted TOOL rows, compaction, budget trimming) can break
     * the pairing — providers then reject the WHOLE request with a 400 and
     * the chat dies until a new conversation is opened. Repair defensively:
     * synthesize any missing ASSISTANT(tool_calls) rows and drop unfixable
     * TOOL rows whose call id cannot be paired.
     */
    internal fun repairToolSequence(
        messages: List<ChatMessage>,
        toolsPresent: Boolean = true
    ): List<ChatMessage> {
        if (!toolsPresent) return deToolify(messages)
        val out = mutableListOf<ChatMessage>()
        var synthCounter = 0
        for (message in messages) {
            if (message.role != ChatMessage.Role.TOOL) {
                out += message
                continue
            }
            val callId = message.toolCallId ?: "call-repair-${synthCounter++}"
            val last = out.lastOrNull()
            when {
                // Already paired — nothing to do.
                last != null && last.role == ChatMessage.Role.ASSISTANT &&
                    last.toolCalls.any { it.callId == callId } -> Unit

                // Consecutive TOOL row: register this call on the assistant
                // row that owns the running tool block (the nearest one above).
                last != null && last.role == ChatMessage.Role.TOOL -> {
                    val assistantIndex = out.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                    if (assistantIndex < 0) continue // no assistant at all — drop orphan
                    out[assistantIndex] = out[assistantIndex].copy(
                        toolCalls = out[assistantIndex].toolCalls + com.neuron.ai.core.provider.ProposedToolCall(
                            callId = callId,
                            toolId = "tool",
                            argumentsJson = "{}"
                        )
                    )
                }

                // Assistant row present but missing THIS call id: append it.
                last != null && last.role == ChatMessage.Role.ASSISTANT ->
                    out[out.lastIndex] = last.copy(
                        toolCalls = last.toolCalls + com.neuron.ai.core.provider.ProposedToolCall(
                            callId = callId,
                            toolId = "tool",
                            argumentsJson = "{}"
                        )
                    )

                // No pairable assistant (start of history / after USER):
                // synthesize the missing assistant tool-request row.
                else -> out += ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        com.neuron.ai.core.provider.ProposedToolCall(
                            callId = callId,
                            toolId = "tool",
                            argumentsJson = "{}"
                        )
                    )
                )
            }
            out += message.copy(toolCallId = callId)
        }
        // A trailing ASSISTANT with tool_calls and NO following TOOL rows is
        // also invalid — strip dangling tool_calls from a trailing assistant.
        val trailing = out.lastOrNull()
        if (trailing != null && trailing.role == ChatMessage.Role.ASSISTANT &&
            trailing.toolCalls.isNotEmpty()
        ) {
            out[out.lastIndex] = trailing.copy(toolCalls = emptyList())
        }
        return out
    }

    /**
     * Converts tool machinery to PLAIN TEXT for requests that carry NO tools
     * array (schema-rejection fallback, tool-less models): strict gateways
     * reject "tool" role messages and tool_calls when no tools are wired.
     * Tool calls/results become bracketed narration inside ASSISTANT rows,
     * merged so the output stays a valid USER/ASSISTANT conversation.
     */
    private fun deToolify(messages: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val narration = StringBuilder()

        fun flushNarration() {
            if (narration.isNotBlank()) {
                out += ChatMessage(ChatMessage.Role.ASSISTANT, narration.toString().trim())
                narration.clear()
            }
        }

        for (message in messages) {
            when (message.role) {
                ChatMessage.Role.TOOL -> narration
                    .append("[tool result] ")
                    .append(message.content.take(800))
                    .append('\n')

                ChatMessage.Role.ASSISTANT -> {
                    message.toolCalls.forEach { call ->
                        narration.append("[used tool: ").append(call.toolId).append("]\n")
                    }
                    if (message.content.isNotBlank()) {
                        narration.append(message.content).append('\n')
                    }
                }

                ChatMessage.Role.USER -> {
                    flushNarration()
                    // Merge consecutive USER rows — some strict endpoints
                    // reject adjacent same-role messages.
                    val last = out.lastOrNull()
                    if (last != null && last.role == ChatMessage.Role.USER) {
                        out[out.lastIndex] = last.copy(
                            content = last.content + "\n" + message.content
                        )
                    } else {
                        out += message
                    }
                }

                else -> {
                    flushNarration()
                    out += message
                }
            }
        }
        flushNarration()
        return out
    }

    private fun toDataUrl(att: Attachment, bytes: ByteArray): String {
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:${att.mimeType};base64,$base64"
    }

    companion object {
        /**
         * Reads a primitive string from a JSON element WITHOUT the kotlinx
         * gotcha where `JsonNull.content` returns the literal string "null".
         * A model's tool-call deltas carry "content": null — naive parsing
         * used to stream the word "null" into the chat.
         */
        fun safeContent(element: kotlinx.serialization.json.JsonElement?): String? {
            if (element == null || element is kotlinx.serialization.json.JsonNull) return null
            return runCatching { element.jsonPrimitive.content }.getOrNull()
        }

        /** Maps an HTTP failure to a user-presentable [NeuronError]. */
        fun mapHttpError(body: String?, code: Int?): NeuronError {
            val json = Json { ignoreUnknownKeys = true }
            val remoteMessage = runCatching {
                body
                    ?.let { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject }
                    ?.get("message")?.jsonPrimitive?.content
            }.getOrNull()

            // The provider's own message is often the ONLY clue (e.g. "model
            // does not support tools") — always surface it.
            val detail = remoteMessage?.let { " — $it" } ?: ""
            return when (code) {
                401 -> NeuronError.Provider("Invalid or missing API key.$detail")
                403 -> NeuronError.Provider("Access denied by the provider.$detail")
                404 -> NeuronError.Provider("Model or endpoint not found. Check the model id and base URL.$detail")
                408 -> NeuronError.Provider("The provider took too long to respond.$detail")
                429 -> NeuronError.Provider("Rate limit reached. Wait a moment and try again.$detail")
                in 500..599 -> NeuronError.Provider("Provider server error. Try again shortly.$detail")
                else -> NeuronError.Provider("Provider request failed (HTTP ${code ?: "?"}).$detail")
            }
        }
    }
}

/**
 * Incremental parser for one SSE stream. OpenAI streams tool calls as
 * argument fragments across chunks; this assembles them until finish_reason.
 */
internal class SseChunkParser(private val json: Json) {

    private val toolCallBuffers = linkedMapOf<String, ToolCallBuffer>()
    private var sawFinish = false

    /** Parses one `data:` payload; empty list for keep-alives and noise. */
    fun parse(payload: String): List<com.neuron.ai.core.provider.StreamEvent> {
        if (payload.trim() == DONE) return listOf(com.neuron.ai.core.provider.StreamEvent.Completed)
        if (payload.isBlank()) return emptyList()

        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (t: Throwable) {
            return emptyList()
        }

        root["error"]?.let { err ->
            val message = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }
                .getOrNull() ?: "Provider error"
            return listOf(com.neuron.ai.core.provider.StreamEvent.Failed(NeuronError.Provider(message)))
        }

        val choices = root["choices"]?.jsonArray ?: return emptyList()
        if (choices.isEmpty()) return emptyList()
        val choice = choices[0].jsonObject

        val events = mutableListOf<com.neuron.ai.core.provider.StreamEvent>()

        choice["delta"]?.jsonObject?.get("content")?.let { content ->
            val text = OpenAIWireCodec.safeContent(content)
            if (!text.isNullOrEmpty()) events += com.neuron.ai.core.provider.StreamEvent.Delta(text)
        }

        choice["delta"]?.jsonObject?.get("tool_calls")?.let { calls ->
            runCatching { calls.jsonArray }.getOrNull()?.forEach { element ->
                val call = element.jsonObject
                val index = OpenAIWireCodec.safeContent(call["index"]) ?: "0"
                val buffer = toolCallBuffers.getOrPut(index) {
                    ToolCallBuffer(
                        OpenAIWireCodec.safeContent(call["id"])
                            ?: "call-" + UUID.randomUUID().toString().take(8)
                    )
                }
                OpenAIWireCodec.safeContent(call["id"])?.let { buffer.callId = it }
                call["function"]?.jsonObject?.let { fn ->
                    OpenAIWireCodec.safeContent(fn["name"])?.let { buffer.name = it }
                    OpenAIWireCodec.safeContent(fn["arguments"])?.let { buffer.arguments.append(it) }
                }
            }
        }

        val finish = OpenAIWireCodec.safeContent(choice["finish_reason"])
        if (finish != null && !sawFinish) {
            sawFinish = true
            toolCallBuffers.values.forEach { buffer ->
                events += com.neuron.ai.core.provider.StreamEvent.ToolCallRequested(
                    callId = buffer.callId,
                    toolId = buffer.name,
                    argumentsJson = buffer.arguments.toString().ifBlank { "{}" }
                )
            }
            toolCallBuffers.clear()
            if (events.isEmpty()) {
                events += com.neuron.ai.core.provider.StreamEvent.Completed
            }
        }

        return events
    }

    private class ToolCallBuffer(
        var callId: String,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    companion object {
        const val DONE = "[DONE]"
    }
}
