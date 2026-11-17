package com.neuron.ai.core.integration.google

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Google OAuth scopes the app requests for the connectors. Kept in one place
 * so the consent request, the UI's permission list and the tools agree.
 */
object GoogleScopes {
    const val OPENID = "openid"
    const val EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    const val PROFILE = "https://www.googleapis.com/auth/userinfo.profile"
    const val DRIVE = "https://www.googleapis.com/auth/drive"
    const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
    const val SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets"
    const val DOCUMENTS = "https://www.googleapis.com/auth/documents"

    val ALL: List<String> = listOf(
        OPENID,
        EMAIL,
        PROFILE,
        DRIVE,
        GMAIL_READONLY,
        GMAIL_SEND,
        GMAIL_COMPOSE,
        SPREADSHEETS,
        DOCUMENTS
    )

    /** Human labels for the Connected Apps permission list. */
    val LABELS: List<Pair<String, String>> = listOf(
        DRIVE to "Google Drive",
        GMAIL_READONLY to "Gmail (read)",
        GMAIL_SEND to "Gmail (send)",
        GMAIL_COMPOSE to "Gmail (drafts)",
        SPREADSHEETS to "Google Sheets",
        DOCUMENTS to "Google Docs"
    )
}

/** A Google account linked to this app through the connector backend. */
data class GoogleAccount(
    val id: String,
    val email: String?,
    val name: String?,
    val picture: String?,
    val scopes: List<String>,
    val connectedAt: Long
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: email ?: "Google account"

    val label: String
        get() = email?.takeIf { it.isNotBlank() } ?: displayName

    fun hasScope(scope: String): Boolean = scopes.contains(scope)
}

/** A short-lived access token returned by the backend for one account. */
data class GoogleAccessToken(
    val accessToken: String,
    val expiresAt: Long,
    val scopes: List<String>
)

/**
 * Failure from the connector layer. [reauthRequired] tells the UI/tools that
 * the user must reconnect the account (revoked/expired grant) rather than
 * retry the same request.
 */
class GoogleConnectorException(
    message: String,
    val reauthRequired: Boolean = false
) : Exception(message)

/**
 * Result of one proxied Google action. [text] is the backend's model-facing
 * summary (some actions return only ids/urls); [payloadJson] keeps the raw
 * response for callers that need structured fields.
 */
data class GoogleActionResult(
    val action: String,
    val text: String?,
    val payloadJson: String
)

/**
 * The single seam the UI and tools use for Google connectors. Implementations
 * keep only short-lived session tokens on the device — refresh tokens are
 * held encrypted by the backend and never leave it.
 */
interface GoogleConnectorRepository {
    val accounts: StateFlow<List<GoogleAccount>>

    /** True when a backend URL was compiled into this build. */
    val configured: Boolean

    /** Exchanges a Google server auth code for a backend session. */
    suspend fun connect(serverAuthCode: String): Result<GoogleAccount>

    /** Refreshes account metadata from the backend. */
    suspend fun syncAccounts(): Result<List<GoogleAccount>>

    /** Returns a fresh short-lived access token for [accountId] (first account by default). */
    suspend fun accessToken(accountIdOrEmail: String? = null): Result<GoogleAccessToken>

    /** Disconnects one account and revokes its grant. */
    suspend fun disconnect(accountId: String): Result<Unit>

    /** Finds a connected account by backend id or email. */
    fun findAccount(accountIdOrEmail: String?): GoogleAccount?

    /**
     * Runs one Google REST action on the connector backend, e.g. `drive.list`
     * or `gmail.search`. The device never performs the Google call itself:
     * [arguments] are forwarded to the Worker, which holds the tokens.
     */
    suspend fun execute(
        action: String,
        arguments: JsonObject,
        accountIdOrEmail: String? = null
    ): Result<GoogleActionResult>
}
