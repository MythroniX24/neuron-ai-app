package com.neuron.ai.core.integration.google

/**
 * The Google products the connectors expose, one connect button each.
 *
 * A single Google grant carries every scope, so one account can cover all four
 * services; the catalog only decides which scopes a "Connect Gmail" tap asks
 * for and how the UI reports what an account can actually do.
 */
enum class GoogleService(
    val id: String,
    val label: String,
    val summary: String,
    /** Scopes requested when this service is connected on its own. */
    val scopes: List<String>,
    /** Scope that must be present for the service tools to work. */
    val requiredScope: String
) {
    GMAIL(
        id = "gmail",
        label = "Gmail",
        summary = "Search, read, draft and send mail",
        scopes = listOf(
            GoogleScopes.GMAIL_READONLY,
            GoogleScopes.GMAIL_SEND,
            GoogleScopes.GMAIL_COMPOSE
        ),
        requiredScope = GoogleScopes.GMAIL_READONLY
    ),
    DRIVE(
        id = "drive",
        label = "Drive",
        summary = "Find, read, create and delete files",
        scopes = listOf(GoogleScopes.DRIVE),
        requiredScope = GoogleScopes.DRIVE
    ),
    DOCS(
        id = "docs",
        label = "Docs",
        summary = "Read and edit documents",
        scopes = listOf(GoogleScopes.DOCUMENTS),
        requiredScope = GoogleScopes.DOCUMENTS
    ),
    SHEETS(
        id = "sheets",
        label = "Sheets",
        summary = "Read and write spreadsheets",
        scopes = listOf(GoogleScopes.SPREADSHEETS),
        requiredScope = GoogleScopes.SPREADSHEETS
    ),
    SLIDES(
        id = "slides",
        label = "Slides",
        summary = "Read, create and edit presentations",
        scopes = listOf(GoogleScopes.PRESENTATIONS),
        requiredScope = GoogleScopes.PRESENTATIONS
    ),
    CALENDAR(
        id = "calendar",
        label = "Calendar",
        summary = "View, create and manage calendar events",
        scopes = listOf(GoogleScopes.CALENDAR),
        requiredScope = GoogleScopes.CALENDAR
    );

    companion object {
        /** Scopes needed to identify the account itself. */
        val IDENTITY_SCOPES: List<String> =
            listOf(GoogleScopes.OPENID, GoogleScopes.EMAIL, GoogleScopes.PROFILE)

        /** Scopes to request for [service], keeping [alreadyGranted] working. */
        fun requestScopes(
            service: GoogleService,
            alreadyGranted: Collection<String> = emptyList()
        ): List<String> =
            (IDENTITY_SCOPES + alreadyGranted + service.scopes).distinct()
    }
}

/** Services this account currently holds a grant for. */
fun GoogleAccount.connectedServices(): Set<GoogleService> =
    GoogleService.entries.filterTo(LinkedHashSet()) { hasScope(it.requiredScope) }

/** True when the account can use [service]. */
fun GoogleAccount.canUse(service: GoogleService): Boolean = hasScope(service.requiredScope)
