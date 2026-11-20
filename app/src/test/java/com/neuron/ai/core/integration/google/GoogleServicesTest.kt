package com.neuron.ai.core.integration.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleServicesTest {

    private fun account(vararg scopes: String) = GoogleAccount(
        id = "owner:sub",
        email = "user@example.com",
        name = "User",
        picture = null,
        scopes = scopes.toList(),
        connectedAt = 0L
    )

    @Test
    fun `every service maps to its own required scope`() {
        assertEquals(GoogleScopes.GMAIL_READONLY, GoogleService.GMAIL.requiredScope)
        assertEquals(GoogleScopes.DRIVE, GoogleService.DRIVE.requiredScope)
        assertEquals(GoogleScopes.DOCUMENTS, GoogleService.DOCS.requiredScope)
        assertEquals(GoogleScopes.SPREADSHEETS, GoogleService.SHEETS.requiredScope)
        assertEquals(GoogleScopes.PRESENTATIONS, GoogleService.SLIDES.requiredScope)
        assertEquals(GoogleScopes.CALENDAR, GoogleService.CALENDAR.requiredScope)
    }

    @Test
    fun `an account with every scope reports all services`() {
        val all = account(*GoogleScopes.ALL.toTypedArray())
        assertEquals(GoogleService.entries.toSet(), all.connectedServices())
    }

    @Test
    fun `no scopes means no services`() {
        assertTrue(account().connectedServices().isEmpty())
    }
}
