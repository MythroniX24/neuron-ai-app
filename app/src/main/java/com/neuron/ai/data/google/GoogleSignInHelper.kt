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
import com.neuron.ai.core.integration.google.GoogleScopes
import kotlinx.coroutines.suspendCancellableCoroutine

/** Outcome of asking Google for offline access. */
sealed interface GoogleConsent {
    /** Consent UI must be shown; launch [pendingIntent]. */
    data class ConsentRequired(val pendingIntent: PendingIntent) : GoogleConsent

    /** Consent was granted and Google returned a server auth code. */
    data class Authorized(val serverAuthCode: String) : GoogleConsent

    /** Consent failed or was cancelled. */
    data class Failed(val message: String) : GoogleConsent
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
 * the `serverClientId` for offline access.
 */
class GoogleSignInHelper(private val context: Context) {

    private val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** True when this build can start the consent flow at all. */
    val configured: Boolean
        get() = webClientId.isNotBlank() && BuildConfig.GOOGLE_BACKEND_URL.isNotBlank()

    /**
     * Starts authorization. Either Google already grants access (returns the
     * code) or it needs the consent screen ([GoogleConsent.ConsentRequired]).
     */
    suspend fun start(): GoogleConsent {
        if (!configured) {
            return GoogleConsent.Failed(
                "This build is missing the Google web client id or the connector backend URL."
            )
        }
        val request = runCatching {
            AuthorizationRequest.Builder()
                .setRequestedScopes(GoogleScopes.ALL.map { Scope(it) })
                // forceCodeForRefreshToken: the backend needs a refresh token
                // on every consent, not just the very first one.
                .requestOfflineAccess(webClientId, true)
                .build()
        }.getOrElse { return GoogleConsent.Failed(it.message ?: "Invalid Google request.") }

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
        if (intent == null) return GoogleConsent.Failed("Google sign-in was cancelled.")
        return runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        }.fold(
            onSuccess = { it.toConsent() },
            onFailure = { consentFrom(it) }
        )
    }

    private fun consentFrom(error: Throwable): GoogleConsent {
        val api = error as? ApiException
        val resolution = api?.status?.resolution
        return if (api != null &&
            api.statusCode == CommonStatusCodes.RESOLUTION_REQUIRED &&
            resolution != null
        ) {
            GoogleConsent.ConsentRequired(resolution)
        } else {
            GoogleConsent.Failed(
                api?.status?.statusMessage ?: error.message ?: "Google sign-in failed."
            )
        }
    }

    private fun AuthorizationResult.toConsent(): GoogleConsent {
        val code = serverAuthCode
        val resolution = pendingIntent
        return when {
            !code.isNullOrBlank() -> GoogleConsent.Authorized(code)
            hasResolution() && resolution != null -> GoogleConsent.ConsentRequired(resolution)
            else -> GoogleConsent.Failed(
                "Google did not return an authorization code. Check that offline access " +
                    "is enabled for this OAuth client."
            )
        }
    }
}
