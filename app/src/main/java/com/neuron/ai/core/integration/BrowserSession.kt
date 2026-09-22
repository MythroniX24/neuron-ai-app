package com.neuron.ai.core.integration

/**
 * Headless browser integration for the browser agent.
 * Implementations wrap an embedded engine (e.g. WebView) behind this seam.
 */
interface BrowserSession {
    /** Navigates and waits until the page reaches a readable state. */
    suspend fun navigate(url: String)

    /** Extracted readable text of the current page. */
    suspend fun pageText(): String

    suspend fun click(selector: String): Boolean
    suspend fun type(selector: String, text: String): Boolean

    fun close()
}
