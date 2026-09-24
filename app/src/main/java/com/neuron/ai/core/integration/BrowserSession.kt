package com.neuron.ai.core.integration

import kotlinx.coroutines.flow.Flow

/** Immutable snapshot of the browser's current page. */
data class BrowserPage(
    val url: String,
    val title: String?,
    val readableText: String,
    val truncated: Boolean,
    val links: List<String> = emptyList()
)

/** Outcome of one browser action — errors are values, never exceptions. */
sealed class BrowserResult {
    data class Success(val page: BrowserPage?, val message: String? = null) : BrowserResult()
    data class Failure(val message: String) : BrowserResult()
}

/**
 * A live headless browser session over a real embedded engine (WebView on
 * device). All webpage content is UNTRUSTED DATA — never instructions. Side-
 * effect actions (click/type/select/submit) route through the caller's
 * permission checks; navigation and reading are read-only.
 */
interface BrowserSession {
    val sessionId: String

    /** Current page snapshot, updated after every navigation/action. */
    val page: Flow<BrowserPage?>

    suspend fun navigate(url: String): BrowserResult
    suspend fun readPage(maxChars: Int = 8_000): BrowserResult
    suspend fun findOnPage(query: String): BrowserResult

    /** External side effects — callers must gate these with permissions. */
    suspend fun clickElement(description: String): BrowserResult
    suspend fun typeText(description: String, text: String): BrowserResult
    suspend fun selectOption(description: String, value: String): BrowserResult
    suspend fun scroll(direction: String): BrowserResult

    fun close()
}

/**
 * Owns browser sessions keyed by conversation context. The AI browser tool
 * and any future user-facing panel share this one infrastructure.
 */
interface BrowserManager {
    suspend fun sessionFor(contextKey: String): BrowserSession
    fun closeSession(contextKey: String)
    fun closeAll()
}
