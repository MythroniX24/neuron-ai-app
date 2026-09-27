package com.neuron.ai.data.web

import com.neuron.ai.core.web.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyless multi-engine tests: Bing HTML + Mojeek scraping adapters parse
 * realistic server-rendered markup, unwrap tracking redirects, and fail
 * gracefully on markup changes. All offline fixtures — no network, no keys.
 */
class KeylessEnginesTest {

    // ---- Bing ------------------------------------------------------------------------------

    private val bingHtml = """
        <html><body>
        <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;u=a1__B64__&amp;ntb=1">Kotlin <strong>coroutines</strong> guide</a></h2><p>Structured concurrency made easy for Android apps.</p></li>
        <li class="b_ad"><h2><a href="https://ads.example.com/x">Sponsored thing</a></h2><p>Buy now.</p></li>
        <li class="b_algo"><h2><a href="https://kotlinlang.org/docs/coroutines-basics.html">Coroutines basics</a></h2><p>Learn coroutine builders and scopes.</p></li>
        </body></html>
    """.trimIndent().replace("__B64__", b64url("https://example.org/kotlin-coroutines"))

    private fun b64url(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private val bing = BingHtmlSearchProvider(
        com.neuron.ai.TestDispatchers,
        okhttp3.OkHttpClient()
    )

    @Test
    fun `bing parse extracts b_algo results and ignores ads`() {
        val results = bing.parse(bingHtml)
        assertEquals(2, results.size)
        assertEquals("Kotlin coroutines guide", results[0].title)
        assertEquals("https://example.org/kotlin-coroutines", results[0].url)
        assertTrue(results[0].snippet!!.contains("Structured concurrency"))
        assertEquals("kotlinlang.org", results[1].domain)
        assertEquals(1, results[0].position)
    }

    @Test
    fun `bing unwrap decodes base64url ck redirect and passes direct urls`() {
        val encoded = b64url("https://a.example.com/x?y=1")
        assertEquals(
            "https://a.example.com/x?y=1",
            bing.unwrap("https://www.bing.com/ck/a?!&u=a1$encoded&ntb=1")
        )
        assertEquals("https://plain.example.com", bing.unwrap("https://plain.example.com"))
        assertNull(bing.unwrap("javascript:alert(1)"))
    }

    @Test
    fun `bing parse fails gracefully on unrelated markup`() {
        assertTrue(bing.parse("<html><body><p>captcha wall</p></body></html>").isEmpty())
    }

    // ---- Mojeek ----------------------------------------------------------------------------

    private val mojeekHtml = """
        <html><body>
        <a class="ob" href="https://docs.example.com/guide">Example guide</a>
        <p class="s">A thorough guide about example things and widgets.</p>
        <a class="ob" href="https://docs.example.com/guide?ref=x">Example guide</a>
        <p class="s">duplicate should be skipped</p>
        <a class="ob" href="https://wiki.example.org/Main">Wiki main page</a>
        <p class="s">Reference material lives here.</p>
        </body></html>
    """.trimIndent()

    private val mojeek = MojeekSearchProvider(
        com.neuron.ai.TestDispatchers,
        okhttp3.OkHttpClient()
    )

    @Test
    fun `mojeek parse extracts ob anchors with snippets and dedupes`() {
        val results = mojeek.parse(mojeekHtml)
        assertEquals(2, results.size)
        assertEquals("https://docs.example.com/guide", results[0].url)
        assertTrue(results[0].snippet!!.contains("thorough guide"))
        assertEquals("wiki.example.org", results[1].domain)
        assertEquals(2, results[1].position)
    }

    @Test
    fun `mojeek parse fails gracefully on unrelated markup`() {
        assertTrue(mojeek.parse("<html><body>blocked</body></html>").isEmpty())
    }

    // ---- Orchestrator query hygiene --------------------------------------------------------

    @Test
    fun `cleaned query drops question words and keeps informative terms`() {
        val orchestrator = SearchOrchestrator(
            SearchProviderRegistry(emptyList()),
            cache = SearchCache()
        )
        assertEquals(
            "quokka habitat size",
            orchestrator.cleanedQuery("What is the quokka habitat size?")
        )
    }

    @Test
    fun `cleaned query returns null when text is already keyword-style`() {
        val orchestrator = SearchOrchestrator(
            SearchProviderRegistry(emptyList()),
            cache = SearchCache()
        )
        assertNull(orchestrator.cleanedQuery("kotlin coroutines"))
    }

    @Test
    fun `search query defaults stay sane`() {
        val q = SearchQuery(text = "x")
        assertEquals(5, q.maxResults)
    }
}
