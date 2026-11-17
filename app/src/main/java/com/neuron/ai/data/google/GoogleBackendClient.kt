package com.neuron.ai.data.google

import com.neuron.ai.BuildConfig
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.google.GoogleAccessToken
import com.neuron.ai.core.integration.google.GoogleActionResult
import com.neuron.ai.core.integration.google.GoogleConnectorException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class BackendAccountDto(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val scopes: List<String> = emptyList(),
    val connectedAt: Long = 0L
)

@Serializable
private data class ExchangeRequest(val serverAuthCode: String)

@Serializable
private data class ExchangeResponse(
    val sessionToken: String,
    val expiresAt: Long = 0L,
    val account: BackendAccountDto
)

@Serializable
private data class RefreshRequest(val accountId: String)

@Serializable
private data class RefreshResponse(
    val accessToken: String,
    val expiresAt: Long = 0L,
    val scopes: List<String> = emptyList()
)

@Serializable
private data class DisconnectRequest(val accountId: String)

@Serializable
private data class AccountsResponse(val accounts: List<BackendAccountDto> = emptyList())

@Serializable
private data class ErrorResponse(val error: String? = null, val message: String? = null)

/**
 * Thin HTTP client for the Neuron AI connector Worker. It never sees a Google
 * refresh token or the Web client secret — only short-lived access tokens and
 * opaque backend session tokens.
 */
class GoogleBackendClient(
    private val dispatchers: DispatcherProvider,
    baseUrl: String = BuildConfig.GOOGLE_BACKEND_URL,
    private val http: OkHttpClient = defaultClient()
) {

    private val root: String = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val configured: Boolean get() = root.isNotBlank()

    private fun requireConfigured() {
        if (!configured) {
            throw GoogleConnectorException(
                "The Google connector backend is not configured in this build."
            )
        }
    }

    private fun url(path: String) = root + path

    private fun errorOf(code: Int, body: String?): GoogleConnectorException {
        val parsed = runCatching { json.decodeFromString(ErrorResponse.serializer(), body.orEmpty()) }
            .getOrNull()
        val key = parsed?.error
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: when (code) {
                401 -> "Google authorization expired. Reconnect the account."
                404 -> "Account not found on the connector backend."
                429 -> "Too many requests. Try again shortly."
                else -> "Connector backend error (HTTP $code)."
            }
        val reauth = key == "reauth_required" || (code == 401 && key != "unauthorized")
        return GoogleConnectorException(message, reauthRequired = reauth)
    }

    private suspend fun send(request: Request): String = withContext(dispatchers.io) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) throw errorOf(response.code, body)
            body.orEmpty()
        }
    }

    suspend fun exchange(serverAuthCode: String, bearer: String?): Pair<BackendAccountDto, String> {
        requireConfigured()
        val payload = json.encodeToString(ExchangeRequest.serializer(), ExchangeRequest(serverAuthCode))
        val builder = Request.Builder()
            .url(url("/auth/google/exchange"))
            .post(payload.toRequestBody(jsonMedia))
        if (!bearer.isNullOrBlank()) builder.header("Authorization", "Bearer $bearer")
        val body = send(builder.build())
        val parsed = runCatching {
            json.decodeFromString(ExchangeResponse.serializer(), body)
        }.getOrElse {
            throw GoogleConnectorException("Unexpected response from the connector backend.")
        }
        return parsed.account to parsed.sessionToken
    }

    suspend fun refresh(accountId: String, bearer: String): GoogleAccessToken {
        requireConfigured()
        val payload = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(accountId))
        val request = Request.Builder()
            .url(url("/auth/google/refresh"))
            .header("Authorization", "Bearer $bearer")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        val body = send(request)
        val parsed = runCatching {
            json.decodeFromString(RefreshResponse.serializer(), body)
        }.getOrElse {
            throw GoogleConnectorException("Unexpected response from the connector backend.")
        }
        return GoogleAccessToken(parsed.accessToken, parsed.expiresAt, parsed.scopes)
    }

    suspend fun disconnect(accountId: String, bearer: String) {
        requireConfigured()
        val payload = json.encodeToString(DisconnectRequest.serializer(), DisconnectRequest(accountId))
        val request = Request.Builder()
            .url(url("/auth/google/disconnect"))
            .header("Authorization", "Bearer $bearer")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        send(request)
    }

    suspend fun accounts(bearer: String): List<BackendAccountDto> {
        requireConfigured()
        val request = Request.Builder()
            .url(url("/auth/google/accounts"))
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        val body = send(request)
        return runCatching {
            json.decodeFromString(AccountsResponse.serializer(), body).accounts
        }.getOrElse {
            throw GoogleConnectorException("Unexpected response from the connector backend.")
        }
    }

    /**
     * Forwards one Google action to the Worker (`POST /google/<action>`).
     * The account id travels in the body so the Worker can resolve which
     * encrypted Google grant to use; the app sends no Google credentials.
     */
    suspend fun execute(
        action: String,
        arguments: JsonObject,
        accountId: String,
        bearer: String
    ): GoogleActionResult {
        requireConfigured()
        val body = JsonObject(arguments + ("accountId" to JsonPrimitive(accountId)))
        val request = Request.Builder()
            .url(url("/google/$action"))
            .header("Authorization", "Bearer $bearer")
            .post(
                json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody(jsonMedia)
            )
            .build()
        val raw = send(request)
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw GoogleConnectorException("Unexpected response from the connector backend.")
        }
        val text = parsed["text"]?.jsonPrimitive?.contentOrNull
        return GoogleActionResult(action, text, raw)
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
