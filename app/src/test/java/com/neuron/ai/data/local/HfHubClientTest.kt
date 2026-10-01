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
}
