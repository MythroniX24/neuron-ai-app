package com.neuron.ai.data.google

import com.neuron.ai.BuildConfig
import com.neuron.ai.core.integration.google.GoogleAccount
import com.neuron.ai.core.integration.google.GoogleConnectorRepository
import com.neuron.ai.core.integration.google.GoogleScopes
import com.neuron.ai.core.integration.google.GoogleService
import com.neuron.ai.core.integration.google.connectedServices
import com.neuron.ai.data.diagnostics.BuildInfo

/**
 * Everything needed to explain a failing Google connection without logcat:
 * the build that is installed (package + signing SHA-1 that the Android OAuth
 * client must match), the client id and backend this APK talks to, the linked
 * accounts with their granted scopes, the last consent error and a live
 * backend probe.
 *
 * Read-only: nothing here changes connector state.
 */
class GoogleConnectorDiagnostics(
    private val helper: GoogleSignInHelper,
    private val backend: GoogleBackendClient,
    private val repository: GoogleConnectorRepository,
    private val build: BuildInfo
) {

    data class AccountReport(
        val id: String,
        val email: String?,
        val name: String?,
        val services: List<GoogleService>,
        val scopes: List<String>,
        val connectedAt: Long
    )

    data class Snapshot(
        val configured: Boolean,
        val build: BuildInfo,
        val webClientId: String,
        val backendUrl: String,
        val accounts: List<AccountReport>,
        val lastSignInError: String?,
        val permissions: List<Pair<String, String>>,
        val requestedScopes: List<String>
    )

    fun snapshot(): Snapshot = Snapshot(
        configured = helper.configured && backend.configured,
        build = build,
        webClientId = helper.maskedWebClientId(),
        backendUrl = backend.baseUrl.ifBlank { "(missing)" },
        accounts = repository.accounts.value.map { it.report() },
        lastSignInError = helper.lastError,
        permissions = GoogleScopes.LABELS,
        requestedScopes = GoogleScopes.ALL
    )

    suspend fun checkBackend(): BackendHealth = backend.health()

    private fun GoogleAccount.report() = AccountReport(
        id = id,
        email = email,
        name = name,
        services = connectedServices().toList(),
        scopes = scopes,
        connectedAt = connectedAt
    )

    companion object {
        /** True when this build has both a web client id and a backend URL. */
        val buildHasConnectorConfig: Boolean
            get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() &&
                BuildConfig.GOOGLE_BACKEND_URL.isNotBlank()
    }
}
