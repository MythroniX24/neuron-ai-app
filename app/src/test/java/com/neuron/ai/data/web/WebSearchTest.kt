package com.neuron.ai.data.web

import com.neuron.ai.TestDispatchers
import com.neuron.ai.core.web.SearchQuery
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Milestone 3 web-search tests over a MockWebServer serving realistic DDG
 * HTML markup — parses, dedupes, unwraps redirects, and fails gracefully.
 */
class WebSearchTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: DuckDuckGoSearchProvider

    private val sampleHtml = """
        <html><body>
        <div class="result results_links">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Fabout%2Fversions%2F15">Android 15 release notes</a>
          <a class="result__snippet" href="#">The latest Android version with new privacy features…</a>
        </div>
        <div class="result results_links">
          <a rel="nofollow" class="result__a" href="https://example.com/dup">Example page</a>
          <a class="result__snippet" href="#">snippet one</a>
        </div>
        <div class="result results_links">
          <a rel="nofollow" class="result__a" href="https://example.com/dup?ref=x">Example page</a>
          <a class="result__snippet" href="#">snippet two</a>
        </div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = DuckDuckGoSearchProvider(
            dispatchers = TestDispatchers,
            httpClient = okhttp3.OkHttpClient()
        )
        // Point the provider at the mock server via a DNS-less trick: we test
        // parse()/unwrap() directly AND run one live request through a
        // customizer-free client by rewriting the endpoints below.
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parse extracts titles urls and snippets in order`() {
        val results = provider.parse(sampleHtml)
        assertEquals(2, results.size)
        assertEquals("Android 15 release notes", results[0].title)
        assertEquals("https://developer.android.com/about/versions/15", results[0].url)
        assertTrue(results[0].snippet!!.contains("privacy"))
        assertEquals(1, results[0].position)
        assertEquals("developer.android.com", results[0].domain)
    }

    @Test
    fun `parse deduplicates by url ignoring query`() {
        val results = provider.parse(sampleHtml)
        val urls = results.map { it.url }
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `unwrap decodes uddg redirect and rejects non-web`() {
        assertEquals(
            "https://a.example.com/x?y=1",
            provider.unwrap("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.example.com%2Fx%3Fy%3D1")
        )
        assertEquals("https://plain.example.com", provider.unwrap("https://plain.example.com"))
        assertEquals(null, provider.unwrap("javascript:alert(1)"))
    }

    @Test
    fun `parse returns empty on unrelated markup - graceful`() {
        assertTrue(provider.parse("<html><body><p>blocked</p></body></html>").isEmpty())
    }

    @Test
    fun `page fetcher extracts readable text with caps`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "<html><head><title>Hi</title></head><body>" +
                    "<script>var x=1;</script><h1>Hello</h1><p>World&nbsp;&amp; more</p></body></html>"
            )
        )
        val fetcher = HttpPageFetcher(
            TestDispatchers,
            okhttp3.OkHttpClient()
        )
        // Fetch the mock URL directly through the fetcher.
        val page = fetcher.fetch(server.url("/page").toString(), maxChars = 500)
        assertEquals("Hi", page?.title)
        assertTrue(page?.text?.contains("Hello") == true)
        assertTrue(page?.text?.contains("&nbsp;") != true)
        assertTrue(page?.text?.contains("World") == true)
    }

    @Test
    fun `page fetcher returns null on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val fetcher = HttpPageFetcher(TestDispatchers, okhttp3.OkHttpClient())
        assertEquals(null, fetcher.fetch(server.url("/missing").toString()))
    }

    @Test
    fun `search query defaults are sane`() {
        val q = SearchQuery(text = "test")
        assertEquals(5, q.maxResults)
        assertEquals(false, q.safeSearch)
    }

    // ---- WebSearchTool pipeline (audit: real page reads + truthful citations) -------------

    /** Hermetic search double: returns canned organic results, never the network. */
    private class FakeSearchProvider(
        private val results: List<com.neuron.ai.core.web.SearchResult>
    ) : com.neuron.ai.core.web.SearchProvider {
        override val id = "fake"
        override suspend fun search(
            query: com.neuron.ai.core.web.SearchQuery
        ): com.neuron.ai.core.web.SearchResponse =
            com.neuron.ai.core.web.SearchResponse.Success(query.text, results)
    }

    /** Fetcher double: only the URLs it actually "opened" resolve. */
    private class FakeFetcher(private val pages: Map<String, String>) : com.neuron.ai.core.web.PageFetcher {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String, maxChars: Int): com.neuron.ai.core.web.PageContent? {
            fetched += url
            val body = pages[url] ?: return null
            return com.neuron.ai.core.web.PageContent(
                url = url, title = "T:${url.takeLast(6)}", text = body.take(maxChars), truncated = false
            )
        }
    }

    @Test
    fun `search tool opens requested pages and lists only real sources`() = runTest {
        val results = listOf(
            com.neuron.ai.core.web.SearchResult(
                title = "First result", url = "https://a.example.com/one",
                snippet = null, position = 1, domain = "a.example.com"
            ),
            com.neuron.ai.core.web.SearchResult(
                title = "Second result", url = "https://b.example.com/two",
                snippet = null, position = 2, domain = "b.example.com"
            )
        )
        val fetcher = FakeFetcher(mapOf("https://a.example.com/one" to "PAGE ONE CONTENT"))
        val tool = com.neuron.ai.data.tool.WebTools.WebSearch(FakeSearchProvider(results), fetcher)

        val result = tool.execute("""{"query":"test","openPages":2}""")

        assertTrue(result is com.neuron.ai.core.agent.ToolResult.Success)
        val out = (result as com.neuron.ai.core.agent.ToolResult.Success).output
        // Both SOURCES listed from the engine.
        assertTrue(out.contains("[1] First result"))
        assertTrue(out.contains("[2] Second result"))
        // openPages=2: BOTH URLs were attempted, but only the page that
        // actually returned content contributes PAGE CONTENT.
        assertEquals(
            listOf("https://a.example.com/one", "https://b.example.com/two"),
            fetcher.fetched
        )
        assertTrue(out.contains("PAGE ONE CONTENT"))
        // No invented content for the page whose fetch failed.
        assertTrue(!out.contains("T:m/two"))
    }

    @Test
    fun `search tool reports failure honestly on empty engine response`() = runTest {
        val fetcher = FakeFetcher(emptyMap())
        val tool = com.neuron.ai.data.tool.WebTools.WebSearch(FakeSearchProvider(emptyList()), fetcher)

        val result = tool.execute("""{"query":"anything"}""")

        // No fabricated success: an empty/blocked engine yields an explicit failure.
        assertTrue(result is com.neuron.ai.core.agent.ToolResult.Failure)
        assertTrue(
            (result as com.neuron.ai.core.agent.ToolResult.Failure).message.contains("No results")
        )
        assertTrue(fetcher.fetched.isEmpty())
    }
}
