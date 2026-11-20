package com.neuron.ai.data.github

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.github.GitHubAccount
import com.neuron.ai.core.integration.github.GitHubRepository
import com.neuron.ai.core.security.SecureCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubRepositoryImpl(
    private val credentials: SecureCredentialStore,
    dispatchers: DispatcherProvider,
    httpClient: OkHttpClient? = null
) : GitHubRepository {

    private val io = dispatchers.io
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _account = MutableStateFlow<GitHubAccount?>(null)
    override val account: StateFlow<GitHubAccount?> = _account.asStateFlow()

    init {
        // Restore account metadata from secure store
        val savedAccount = credentials.get(KEY_ACCOUNT_JSON)?.let { raw ->
            runCatching { json.decodeFromString<GitHubAccount>(raw) }.getOrNull()
        }
        _account.value = savedAccount
    }

    override suspend fun connect(token: String): Result<GitHubAccount> = withContext(io) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("GitHub token cannot be blank."))
        }

        val request = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $cleanToken")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Neuron-AI-Android")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull() ?: "HTTP ${response.code}: ${response.message}"
                    return@withContext Result.failure(IOException("GitHub authentication failed: $msg"))
                }

                val obj = json.parseToJsonElement(body).jsonObject
                val login = obj["login"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IOException("Invalid response: missing login"))
                val name = obj["name"]?.jsonPrimitive?.content
                val avatarUrl = obj["avatar_url"]?.jsonPrimitive?.content
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.content
                val publicRepos = obj["public_repos"]?.jsonPrimitive?.intOrNull ?: 0

                val accountObj = GitHubAccount(
                    login = login,
                    name = name,
                    avatarUrl = avatarUrl,
                    htmlUrl = htmlUrl,
                    publicRepos = publicRepos,
                    connectedAt = System.currentTimeMillis()
                )

                credentials.put(KEY_TOKEN, cleanToken)
                credentials.put(KEY_ACCOUNT_JSON, json.encodeToString(accountObj))
                _account.value = accountObj

                Result.success(accountObj)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> = withContext(io) {
        credentials.remove(KEY_TOKEN)
        credentials.remove(KEY_ACCOUNT_JSON)
        _account.value = null
        Result.success(Unit)
    }

    override suspend fun getAccount(): GitHubAccount? = _account.value

    override suspend fun execute(
        endpoint: String,
        method: String,
        bodyJson: String?
    ): Result<String> = withContext(io) {
        val path = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            endpoint
        } else {
            "https://api.github.com/" + endpoint.trimStart('/')
        }

        val token = credentials.get(KEY_TOKEN)
        val builder = Request.Builder()
            .url(path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Neuron-AI-Android")

        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((bodyJson ?: "{}").toRequestBody(mediaType))
            "PUT" -> builder.put((bodyJson ?: "{}").toRequestBody(mediaType))
            "PATCH" -> builder.patch((bodyJson ?: "{}").toRequestBody(mediaType))
            "DELETE" -> {
                if (bodyJson != null) builder.delete(bodyJson.toRequestBody(mediaType))
                else builder.delete()
            }
            else -> builder.get()
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(body)
                } else {
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull() ?: "HTTP ${response.code}: ${response.message}"
                    Result.failure(IOException("GitHub API error: $msg"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val KEY_TOKEN = "github_access_token"
        const val KEY_ACCOUNT_JSON = "github_account_json"
    }
}
