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
    }

    @Test
    fun `connect request keeps identity scopes and the service scopes`() {
        val scopes = GoogleService.requestScopes(GoogleService.DRIVE)
        assertTrue(scopes.contains(GoogleScopes.OPENID))
        assertTrue(scopes.contains(GoogleScopes.EMAIL))
        assertTrue(scopes.contains(GoogleScopes.PROFILE))
        assertTrue(scopes.contains(GoogleScopes.DRIVE))
        assertFalse(scopes.contains(GoogleScopes.GMAIL_READONLY))
    }

    @Test
    fun `connect request keeps scopes an existing account already holds`() {
        val scopes = GoogleService.requestScopes(
            service = GoogleService.SHEETS,
            alreadyGranted = listOf(GoogleScopes.GMAIL_READONLY, GoogleScopes.DRIVE)
        )
        assertTrue(scopes.contains(GoogleScopes.SPREADSHEETS))
        assertTrue(scopes.contains(GoogleScopes.GMAIL_READONLY))
        assertTrue(scopes.contains(GoogleScopes.DRIVE))
        assertEquals(scopes.size, scopes.distinct().size)
    }

    @Test
    fun `an account reports exactly the services it can use`() {
        val driveOnly = account(GoogleScopes.DRIVE)
        assertEquals(setOf(GoogleService.DRIVE), driveOnly.connectedServices())
        assertTrue(driveOnly.canUse(GoogleService.DRIVE))
        assertFalse(driveOnly.canUse(GoogleService.GMAIL))
    }

    @Test
    fun `an account with every scope reports all four services`() {
        val all = account(*GoogleScopes.ALL.toTypedArray())
        assertEquals(GoogleService.entries.toSet(), all.connectedServices())
    }

    @Test
    fun `no scopes means no services`() {
        assertTrue(account().connectedServices().isEmpty())
    }
}
