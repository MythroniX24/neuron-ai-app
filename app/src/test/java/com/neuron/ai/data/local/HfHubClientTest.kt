package com.neuron.ai.data.local

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HfHubClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: HfHubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HfHubClient(baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search parses repo id downloads and license tag`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF","downloads":123456,"likes":99,
                   "tags":["gguf","license:apache-2.0","region:us"]},
                  {"id":"onnx-community/something","downloads":10,"likes":1,"tags":["onnx"]}]"""
            )
        )

        val results = client.search("tinyllama")

        // The onnx repo is filtered OUT (not runnable by this app).
        assertEquals(1, results.size)
        assertEquals("TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF", results[0].repoId)
        assertEquals(123456L, results[0].downloads)
        assertEquals("apache-2.0", results[0].license)
        // Search requests hit the gguf-filtered endpoint.
        val path = server.takeRequest().path!!
        assertTrue(path.contains("filter=gguf"))
        assertTrue(path.contains("sort=downloads"))
    }

    @Test
    fun `files extracts gguf paths with size and lfs sha256`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"type":"file","size":21855,"path":"README.md"},
                  {"type":"file","size":592500096,
                   "lfs":{"oid":"abc123","size":592500096},
                   "path":"tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"},
                  {"type":"file","size":483116416,
                   "lfs":{"oid":"def456","size":483116416},
                   "path":"tinyllama-1.1b-chat-v1.0.Q2_K.gguf"}]"""
            )
        )

        val files = client.files("TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF")

        // The repo's "/" must survive URL building (a %2F path 404s on the Hub).
        assertTrue(
            server.takeRequest().path!!
                .startsWith("/api/models/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/tree/main")
        )
        assertEquals(2, files.size)
        assertEquals("tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf", files[0].fileName)
        assertEquals(592500096L, files[0].sizeBytes)
        assertEquals("abc123", files[0].sha256)
    }

    @Test
    fun `quant labels are parsed from variant file names`() {
        val result = HfHubClient.SearchResult(
            repoId = "some/repo",
            downloads = 0,
            likes = 0,
            license = null,
            variants = listOf(
                HfHubClient.SearchResult.Variant("model.Q4_K_M.gguf", 1, null),
                HfHubClient.SearchResult.Variant("model.Q8_0.gguf", 1, null)
            )
        )

        assertEquals(listOf("Q4_K_M", "Q8_0"), result.quantLevels)
    }

    @Test
    fun `download url resolves to the repo file path`() {
        val url = client.downloadUrl("some/repo", "model.gguf")
        assertTrue(url.endsWith("/some/repo/resolve/main/model.gguf"))
    }

    @Test
    fun `a gated repo is reported as gated instead of an empty list`() {
        // google/gemma-* hides its file list behind a 401 until the user
        // accepts the licence on the Hub — the reason downloads used to fail
        // with no explanation.
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"gated\"}"))

        val listing = client.listing("google/gemma-2-9b-it-GGUF")

        assertTrue(listing.gated)
        assertTrue(listing.variants.isEmpty())
        assertNull(listing.error)
        assertTrue(listing.unavailableReason!!.contains("gated"))
    }

    @Test
    fun `missing and rate limited repos are distinguished`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val missing = client.listing("some/deleted-repo")
        assertTrue(missing.notFound)
        assertTrue(missing.unavailableReason!!.contains("no longer available"))

        server.enqueue(MockResponse().setResponseCode(429))
        val limited = client.listing("some/busy-repo")
        assertTrue(limited.throttled)
        assertTrue(limited.unavailableReason!!.contains("rate-limiting"))
    }

    @Test
    fun `listing drops split shard files and says so when only shards exist`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"type":"file","size":100,"path":"m-00001-of-00002.gguf"},
                  {"type":"file","size":100,"path":"m-00002-of-00002.gguf"}]"""
            )
        )
        val onlyShards = client.listing("some/repo")
        assertTrue(onlyShards.variants.isEmpty())
        assertTrue(onlyShards.unavailableReason!!.contains("multi-shard"))

        server.enqueue(
            MockResponse().setBody(
                """[{"type":"file","size":100,"path":"m-00001-of-00002.gguf"},
                  {"type":"file","size":700,"lfs":{"oid":"a"},"path":"m-Q4_K_M.gguf"}]"""
            )
        )
        val mixed = client.listing("some/repo")
        assertEquals(1, mixed.variants.size)
        assertEquals("m-Q4_K_M.gguf", mixed.variants.first().fileName)
    }

    @Test
    fun `shard and quant detection`() {
        assertTrue(HfHubClient.isShardFile("Qwen3-30B-Q4_K_M-00001-of-00002.gguf"))
        assertTrue(!HfHubClient.isShardFile("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("Q4_K_M", HfHubClient.quantOf("model-Q4_K_M.gguf"))
        assertEquals("IQ4_XS", HfHubClient.quantOf("model.IQ4_XS.gguf"))
        assertNull(HfHubClient.quantOf("model.gguf"))
    }

    @Test
    fun `variant selection prefers the catalog quant then degrades gracefully`() {
        val variants = listOf(
            HfHubClient.SearchResult.Variant("m-Q8_0.gguf", 900, null),
            HfHubClient.SearchResult.Variant("m-Q4_K_M.gguf", 500, null),
            HfHubClient.SearchResult.Variant("m-Q2_K.gguf", 300, null)
        )
        // Exact preference hit wins even though a bigger file exists.
        assertEquals(
            "m-Q4_K_M.gguf",
            HfHubClient.selectVariant(variants)?.fileName
        )
        // No preferred quant present → smallest quantized file, never the f16.
        // Q5_K_M is the only quantized file in this pool, so it wins.
        assertEquals(
            "m-Q5_K_M.gguf",
            HfHubClient.selectVariant(
                listOf(
                    HfHubClient.SearchResult.Variant("m-f16.gguf", 1200, null),
                    HfHubClient.SearchResult.Variant("m-Q5_K_M.gguf", 800, null)
                ),
                preferredQuants = listOf("Q4_K_M")
            )?.fileName
        )
        assertNull(HfHubClient.selectVariant(emptyList()))
    }
}
