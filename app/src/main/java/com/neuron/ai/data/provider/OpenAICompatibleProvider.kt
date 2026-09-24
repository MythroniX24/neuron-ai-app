package com.neuron.ai.data.provider

import com.neuron.ai.core.error.NeuronError
import com.neuron.ai.core.provider.AIProvider
import com.neuron.ai.core.provider.ChatMessage
import com.neuron.ai.core.provider.Completion
import com.neuron.ai.core.provider.CompletionRequest
import com.neuron.ai.core.provider.Model
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.core.provider.StreamEvent
import com.neuron.ai.core.security.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Carrier for [NeuronError]s across suspend boundaries. */
class ProviderException(val error: NeuronError) : Exception(error.message)

/**
 * OpenAI-compatible chat-completions provider over OkHttp + SSE.
 * Works with OpenAI, OpenRouter, Groq, Together, Ollama, LM Studio, vLLM…
 * The API key never leaves [SecureCredentialStore] except into the
 * Authorization header of a single request.
 */
class OpenAICompatibleProvider(
    override val id: String,
    private val config: ProviderConfig,
    private val credentials: SecureCredentialStore,
    httpClient: OkHttpClient? = null,
    json: Json = Json { ignoreUnknownKeys = true }
) : AIProvider {

    override val displayName: String = config.displayName

    private val wire = OpenAIWireCodec(json)
    private val jsonParser: Json = json

    private val client: OkHttpClient = (httpClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Injected by the DI layer so vision bytes come from the attachment store. */
    var imageByteLoader: (suspend (attachmentId: String) -> ByteArray)? = null

    private fun authHeader(): String? =
        credentials.get(config.credentialKey)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Request.Builder.withProviderHeaders(): Request.Builder {
        header("Content-Type", "application/json")
        authHeader()?.let { header("Authorization", "Bearer $it") }
        config.customHeaders.forEach { (name, value) -> header(name, value) }
        return this
    }

    private fun endpoint(path: String): String = config.baseUrl.trimEnd('/') + path

    // ---- Model discovery ------------------------------------------------------

    override suspend fun listModels(): List<Model> {
        // Explicit user-configured model ids win over discovery.
        if (config.modelIds.isNotEmpty()) {
            return config.modelIds.map { Model(id = it, displayName = it) }
        }

        val request = Request.Builder()
            .url(endpoint("/models"))
            .withProviderHeaders()
            .get()
            .build()

        return try {
            val body = execute(request)
            parseModels(body)
        } catch (e: ProviderException) {
            throw e
        } catch (t: Throwable) {
            throw ProviderException(NeuronError.Provider("Could not list models: ${t.message}"))
        }
    }

    private fun parseModels(body: String): List<Model> = runCatching {
        val root = jsonParser.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: emptyList()
        data.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val modelId = OpenAIWireCodec.safeContent(obj["id"]) ?: return@mapNotNull null
            Model(id = modelId, displayName = modelId)
        }
    }.getOrDefault(emptyList())

    // ---- One-shot completion ---------------------------------------------------

    override suspend fun complete(request: CompletionRequest): Completion {
        val payload = wire.encodeRequest(request, imageDataOf(request))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(endpoint("/chat/completions"))
            .withProviderHeaders()
            .post(payload)
            .build()

        val body = try {
            execute(httpRequest)
        } catch (e: ProviderException) {
            throw e
        } catch (t: Throwable) {
            throw ProviderException(NeuronError.Provider("Request failed: ${t.message}"))
        }

        val choice = runCatching {
            jsonParser.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.let { it.jsonObject }
        }.getOrNull() ?: throw ProviderException(NeuronError.Provider("Malformed response from provider."))

        val content = OpenAIWireCodec.safeContent(
            choice["message"]?.jsonObject?.get("content")
        ) ?: ""

        return Completion(
            message = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = content),
            modelId = request.model.id
        )
    }

    // ---- Streaming ----------------------------------------------------------------

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = callbackFlow {
        val parser = SseChunkParser(jsonParser)

        val payload = wire.encodeRequest(request, imageDataOf(request))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(endpoint("/chat/completions"))
            .withProviderHeaders()
            .post(payload)
            .build()

        val call = client.newCall(httpRequest)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Failed(mapFailure(e)))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorBody = runCatching { resp.body?.string() }.getOrNull()
                        trySend(StreamEvent.Failed(OpenAIWireCodec.mapHttpError(errorBody, resp.code)))
                        close()
                        return
                    }

                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(StreamEvent.Failed(NeuronError.Provider("Empty response stream.")))
                        close()
                        return
                    }

                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            runCatching {
                                parser.parse(line.removePrefix("data:").trim())
                            }.getOrNull()?.forEach { event ->
                                trySend(event)
                            }
                        }
                    } catch (e: IOException) {
                        trySend(StreamEvent.Failed(mapFailure(e)))
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    // ---- Helpers -----------------------------------------------------------------

    private fun imageDataOf(request: CompletionRequest): Map<String, ByteArray> {
        val loader = imageByteLoader ?: return emptyMap()
        return request.messages
            .flatMap { msg -> msg.attachments.filter { it.isImage }.map { it.id } }
            .distinct()
            .mapNotNull { id ->
                runCatching { loader(id) }.getOrNull()?.let { id to it }
            }
            .toMap()
    }

    private fun mapFailure(e: IOException): NeuronError = when {
        e.message?.contains("timeout", ignoreCase = true) == true ->
            NeuronError.Provider("Connection timed out. Check the base URL and your network.")
        e.message?.contains("Unable to resolve", ignoreCase = true) == true ->
            NeuronError.Provider("Could not reach the provider. Check the base URL and internet.")
        else -> NeuronError.Provider("Connection failed: ${e.message ?: "network error"}")
    }

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(ProviderException(mapFailure(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyString = runCatching { resp.body?.string() }.getOrNull()
                        if (!resp.isSuccessful) {
                            continuation.resumeWithException(
                                ProviderException(OpenAIWireCodec.mapHttpError(bodyString, resp.code))
                            )
                        } else {
                            continuation.resume(bodyString.orEmpty())
                        }
                    }
                }
            })
        }
}
