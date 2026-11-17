package com.neuron.ai.data.google

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.integration.google.GoogleAccount
import com.neuron.ai.core.integration.google.GoogleAccessToken
import com.neuron.ai.core.integration.google.GoogleActionResult
import com.neuron.ai.core.integration.google.GoogleConnectorException
import com.neuron.ai.core.integration.google.GoogleConnectorRepository
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.security.SecureCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Persisted connector state. Session tokens are opaque to Google and are kept
 * in [SecureCredentialStore] (Android Keystore-backed). Google refresh tokens
 * are never stored on the device. Accounts remember only what the UI needs plus
 * the backend session token used for every request.
 */
@Serializable
private data class StoredAccount(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val scopes: List<String> = emptyList(),
    val connectedAt: Long = 0L,
    val sessionToken: String
) {
    fun toDomain() = GoogleAccount(id, email, name, picture, scopes, connectedAt)
}

class GoogleConnectorRepositoryImpl(
    private val backend: GoogleBackendClient,
    private val credentials: SecureCredentialStore,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger? = null
) : GoogleConnectorRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _stored = MutableStateFlow(load())
    private val _accounts = MutableStateFlow(_stored.value.map { it.toDomain() })

    override val accounts: StateFlow<List<GoogleAccount>> = _accounts.asStateFlow()

    override val configured: Boolean get() = backend.configured

    override fun findAccount(accountIdOrEmail: String?): GoogleAccount? =
        findStored(accountIdOrEmail)?.toDomain()

    override suspend fun connect(serverAuthCode: String): Result<GoogleAccount> = try {
        if (serverAuthCode.isBlank()) {
            Result.failure(GoogleConnectorException("Google did not return an authorization code."))
        } else {
            // Link additional accounts under the same backend user when we
            // already have a session for this app install.
            val bearer = _stored.value.firstOrNull()?.sessionToken
            val (dto, session) = backend.exchange(serverAuthCode, bearer)
            val stored = StoredAccount(
                id = dto.id,
                email = dto.email,
                name = dto.name,
                picture = dto.picture,
                scopes = dto.scopes,
                connectedAt = dto.connectedAt,
                sessionToken = session
            )
            mutex.withLock {
                apply(_stored.value.filterNot { it.id == stored.id } + stored)
            }
            Result.success(stored.toDomain())
        }
    } catch (t: Throwable) {
        logger?.w("GoogleConnector", "Connect failed", t)
        Result.failure(t.asConnectorError())
    }

    override suspend fun syncAccounts(): Result<List<GoogleAccount>> = try {
        val bearer = _stored.value.firstOrNull()?.sessionToken
            ?: return Result.success(_accounts.value)
        val remote = backend.accounts(bearer)
        mutex.withLock {
            val byId = remote.associateBy { it.id }
            val merged = _stored.value.mapNotNull { local ->
                val fresh = byId[local.id] ?: return@mapNotNull null
                local.copy(
                    email = fresh.email ?: local.email,
                    name = fresh.name ?: local.name,
                    picture = fresh.picture ?: local.picture,
                    scopes = fresh.scopes.ifEmpty { local.scopes },
                    connectedAt = fresh.connectedAt.takeIf { it > 0 } ?: local.connectedAt
                )
            }
            if (merged != _stored.value) apply(merged)
        }
        Result.success(_accounts.value)
    } catch (t: Throwable) {
        logger?.w("GoogleConnector", "Account sync failed", t)
        Result.failure(t.asConnectorError())
    }

    override suspend fun accessToken(accountIdOrEmail: String?): Result<GoogleAccessToken> = try {
        val stored = findStored(accountIdOrEmail)
        if (stored == null) {
            Result.failure(
                GoogleConnectorException(
                    "No Google account is connected. Ask the user to connect one in " +
                        "Settings → Connected Apps."
                )
            )
        } else {
            Result.success(backend.refresh(stored.id, stored.sessionToken))
        }
    } catch (t: Throwable) {
        logger?.w("GoogleConnector", "Token refresh failed", t)
        Result.failure(t.asConnectorError())
    }

    override suspend fun execute(
        action: String,
        arguments: JsonObject,
        accountIdOrEmail: String?
    ): Result<GoogleActionResult> = try {
        val stored = findStored(accountIdOrEmail)
        if (stored == null) {
            Result.failure(
                GoogleConnectorException(
                    "No Google account is connected. Ask the user to connect one in " +
                        "Settings → Connected Apps."
                )
            )
        } else {
            Result.success(backend.execute(action, arguments, stored.id, stored.sessionToken))
        }
    } catch (t: Throwable) {
        logger?.w("GoogleConnector", "Action $action failed", t)
        Result.failure(t.asConnectorError())
    }

    override suspend fun disconnect(accountId: String): Result<Unit> = try {
        val stored = findStored(accountId)
            ?: return Result.failure(GoogleConnectorException("Account is not connected."))
        backend.disconnect(stored.id, stored.sessionToken)
        mutex.withLock {
            apply(_stored.value.filterNot { it.id == stored.id })
        }
        Result.success(Unit)
    } catch (t: Throwable) {
        logger?.w("GoogleConnector", "Disconnect failed", t)
        // A backend failure (e.g. already revoked) must not trap the user:
        // drop the local session so they can reconnect cleanly.
        mutex.withLock {
            apply(_stored.value.filterNot { it.id == accountId })
        }
        Result.failure(t.asConnectorError())
    }

    // ---- persistence ------------------------------------------------------

    private fun findStored(accountIdOrEmail: String?): StoredAccount? {
        val list = _stored.value
        if (list.isEmpty()) return null
        if (accountIdOrEmail.isNullOrBlank()) return list.first()
        val needle = accountIdOrEmail.trim()
        return list.firstOrNull { it.id == needle }
            ?: list.firstOrNull { it.email.equals(needle, ignoreCase = true) }
            ?: list.firstOrNull {
                it.email?.substringBefore('@')?.equals(needle, ignoreCase = true) == true
            }
    }

    /** Persists and publishes one new account list. Call inside [mutex]. */
    private fun apply(updated: List<StoredAccount>) {
        persist(updated)
        _stored.value = updated
        _accounts.value = updated.map { it.toDomain() }
    }

    private fun load(): List<StoredAccount> {
        val raw = credentials.get(ACCOUNTS_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredAccount.serializer()), raw)
        }.getOrElse {
            logger?.w("GoogleConnector", "Could not read stored accounts", it)
            emptyList()
        }
    }

    private fun persist(accounts: List<StoredAccount>) {
        if (accounts.isEmpty()) {
            credentials.remove(ACCOUNTS_KEY)
        } else {
            credentials.put(
                ACCOUNTS_KEY,
                json.encodeToString(ListSerializer(StoredAccount.serializer()), accounts)
            )
        }
    }

    private fun Throwable.asConnectorError(): GoogleConnectorException =
        this as? GoogleConnectorException
            ?: GoogleConnectorException(message ?: "Google connector error.")

    private companion object {
        const val ACCOUNTS_KEY = "google.connector.accounts"
    }
}
