package com.neuron.ai.data.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.neuron.ai.BuildConfig
import com.neuron.ai.core.diagnostics.DiagnosticsLog
import com.neuron.ai.core.integration.google.GoogleScopes
import kotlinx.coroutines.suspendCancellableCoroutine

/** Outcome of asking Google for offline access. */
sealed interface GoogleConsent {
    /** Consent UI must be shown; launch [pendingIntent]. */
    data class ConsentRequired(val pendingIntent: PendingIntent) : GoogleConsent

    /** Consent was granted and Google returned a server auth code. */
    data class Authorized(val serverAuthCode: String) : GoogleConsent

    /** Consent failed or was cancelled. [code] is the Google status code when known. */
    data class Failed(val message: String, val code: Int? = null) : GoogleConsent
}

/**
 * Google consent for the connector backend.
 *
 * The app asks Google only for a **server auth code** (offline access). That
 * code goes to the Worker, which exchanges it for access + refresh tokens and
 * keeps the refresh token encrypted. No client secret, refresh token or
 * long-lived Google access token ever reaches the device.
 *
 * The Android OAuth client is matched by package name + signing certificate,
 * so nothing about it lives in code; only the WEB client id is needed here as
 * the `serverClientId` for offline access. Every step is written to the
 * diagnostics log because a Google consent failure otherwise only says
 * "sign-in failed".
 */
class GoogleSignInHelper(
    private val context: Context,
    private val diagnostics: DiagnosticsLog? = null
) {

    val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** True when this build can start the consent flow at all. */
    val configured: Boolean
        get() = webClientId.isNotBlank() && BuildConfig.GOOGLE_BACKEND_URL.isNotBlank()

    /** Last human-readable failure, shown next to the account list. */
    var lastError: String? = null
        private set

    /**
     * Starts authorization. Either Google already grants access (returns the
     * code) or it needs the consent screen ([GoogleConsent.ConsentRequired]).
     *
     * [scopes] lets one service be connected at a time while keeping the
     * scopes an already-linked account holds.
     */
    suspend fun start(scopes: List<String> = GoogleScopes.ALL): GoogleConsent {
        if (!configured) {
            return fail(
                "This build is missing the Google web client id or the connector backend URL."
            )
        }
        diagnostics?.log(
            "GOOGLE",
            "consent requested",
            "${scopes.size} scopes, web client ${maskedWebClientId()}"
        )
        val request = runCatching {
            AuthorizationRequest.Builder()
                .setRequestedScopes(scopes.map { Scope(it) })
                // forceCodeForRefreshToken: the backend needs a refresh token
                // on every consent, not just the very first one.
                .requestOfflineAccess(webClientId, true)
                .build()
        }.getOrElse {
            return fail(it.message ?: "Invalid Google request.")
        }

        val client = Identity.getAuthorizationClient(context)
        return suspendCancellableCoroutine { continuation ->
            client.authorize(request)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(result.toConsent()))
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(consentFrom(error)))
                    }
                }
        }
    }

    /** Parses the consent screen's result. */
    fun resultFrom(intent: Intent?): GoogleConsent {
        if (intent == null) return fail("Google sign-in was cancelled.")
        return runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        }.fold(
            onSuccess = { it.toConsent() },
            onFailure = { consentFrom(it) }
        )
    }

    /** Short web client id for the Debug console ("…us7"). */
    fun maskedWebClientId(): String {
        if (webClientId.isBlank()) return "(missing)"
        val tail = webClientId.takeLast(8)
        return "…$tail"
    }

    private fun fail(message: String): GoogleConsent.Failed {
        lastError = message
        diagnostics?.log("GOOGLE", "consent failed", message)
        return GoogleConsent.Failed(message)
    }

    private fun consentFrom(error: Throwable): GoogleConsent {
        val api = error as? ApiException
        val resolution = api?.status?.resolution
        if (api != null &&
            api.statusCode == CommonStatusCodes.RESOLUTION_REQUIRED &&
            resolution != null
        ) {
            diagnostics?.log("GOOGLE", "consent screen required", "code ${api.statusCode}")
            return GoogleConsent.ConsentRequired(resolution)
        }
        val code = api?.statusCode
        val message = describe(api, error)
        lastError = message
        diagnostics?.log("GOOGLE", "consent failed", "code=${code ?: -1} $message")
        return GoogleConsent.Failed(message, code)
    }

    /**
     * Google's message for a consent failure is usually empty, so the status
     * code and the fix it implies are stated explicitly.
     */
    private fun describe(api: ApiException?, error: Throwable): String {
        if (api == null) return error.message ?: "Google sign-in failed."
        val raw = api.status.statusMessage ?: api.message ?: "Google sign-in failed."
        val hint = when (api.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                " — the Android OAuth client does not match this APK: check the package " +
                    "name and the signing SHA-1 shown in Connected apps → Diagnostics."
            7 -> " — the network is unavailable."
            17 -> " — Google Play services must be updated."
            12500 -> " — Google could not sign this account in for this app."
            12501 -> " — sign-in was cancelled."
            12502 -> " — another sign-in is already running."
            else -> ""
        }
        return "Google sign-in failed (code ${api.statusCode}): $raw$hint"
    }

    private fun AuthorizationResult.toConsent(): GoogleConsent {
        val code = serverAuthCode
        val resolution = pendingIntent
        return when {
            !code.isNullOrBlank() -> {
                diagnostics?.log("GOOGLE", "authorization code received")
                GoogleConsent.Authorized(code)
            }
            hasResolution() && resolution != null -> {
                diagnostics?.log("GOOGLE", "consent screen required", "pending intent")
                GoogleConsent.ConsentRequired(resolution)
            }
            else -> fail(
                "Google did not return an authorization code. Check that offline access " +
                    "is enabled for this OAuth client."
            )
        }
    }
}
