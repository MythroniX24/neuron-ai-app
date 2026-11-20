package com.neuron.ai.data.web

import com.neuron.ai.TestDispatchers
import com.neuron.ai.core.conversation.WebSource
import com.neuron.ai.data.tool.WebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngSearchProviderTest {

    private val sampleSearxngJson = """
        {
          "query": "android kotlin compose",
          "number_of_results": 3,
          "results": [
            {
              "url": "https://developer.android.com/jetpack/compose",
              "title": "Jetpack Compose | Android Developers",
              "content": "Jetpack Compose is Android's modern toolkit for building native UI.",
              "engine": "google"
            },
            {
              "url": "https://github.com/JetBrains/compose-multiplatform",
              "title": "Compose Multiplatform",
              "content": "Compose Multiplatform is a declarative UI framework for Kotlin.",
              "engine": "github"
            },
            {
              "url": "https://developer.android.com/jetpack/compose#duplicate",
              "title": "Duplicate URL entry",
              "content": "Another snippet",
              "engine": "bing"
            }
          ],
          "suggestions": ["compose multiplatform", "compose animation"]
        }
    """.trimIndent()

    @Test
    fun `parseJson extracts valid results and deduplicates urls`() {
        val provider = SearxngSearchProvider(TestDispatchers)
        val results = provider.parseJson(sampleSearxngJson, maxResults = 5)

        assertEquals(2, results.size)
        assertEquals("Jetpack Compose | Android Developers", results[0].title)
        assertEquals("https://developer.android.com/jetpack/compose", results[0].url)
        assertEquals("developer.android.com", results[0].domain)
        assertTrue(results[0].snippet!!.contains("modern toolkit"))

        assertEquals("Compose Multiplatform", results[1].title)
        assertEquals("https://github.com/JetBrains/compose-multiplatform", results[1].url)
        assertEquals("github.com", results[1].domain)
    }

    @Test
    fun `extractWebSources parses structured marker`() {
        val jsonPayload = """[{"title":"Doc","url":"https://example.com/doc","domain":"example.com","faviconUrl":"https://fav.ico"}]"""
        val rawText = "Here are the results:\n\n${WebTools.SOURCES_MARKER_PREFIX}$jsonPayload${WebTools.SOURCES_MARKER_SUFFIX}"

        val extracted = WebTools.extractWebSources(rawText)
        assertEquals(1, extracted.size)
        assertEquals("Doc", extracted[0].title)
        assertEquals("https://example.com/doc", extracted[0].url)
        assertEquals("example.com", extracted[0].domain)
        assertEquals("https://fav.ico", extracted[0].faviconUrl)
    }

    @Test
    fun `extractWebSources falls back to text parsing if marker missing`() {
        val text = """
            SOURCES:
            [1] Android Guide
                https://android.com/guide
                Official guide for developers
            [2] Kotlin Docs
                https://kotlinlang.org/docs
                Kotlin documentation
        """.trimIndent()

        val extracted = WebTools.extractWebSources(text)
        assertEquals(2, extracted.size)
        assertEquals("Android Guide", extracted[0].title)
        assertEquals("https://android.com/guide", extracted[0].url)
        assertEquals("android.com", extracted[0].domain)
        assertNotNull(extracted[0].faviconUrl)

        assertEquals("Kotlin Docs", extracted[1].title)
        assertEquals("https://kotlinlang.org/docs", extracted[1].url)
        assertEquals("kotlinlang.org", extracted[1].domain)
    }
}
