package com.neuron.ai.core.integration.github

import com.neuron.ai.TestDispatchers
import com.neuron.ai.core.security.InMemorySecureCredentialStore
import com.neuron.ai.data.github.GitHubRepositoryImpl
import com.neuron.ai.data.tool.GitHubConnectorTools
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySecureCredentialStore
    private lateinit var repository: GitHubRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySecureCredentialStore()
        repository = GitHubRepositoryImpl(
            credentials = store,
            dispatchers = TestDispatchers,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `initial account is null when no credentials stored`() = runTest {
        assertNull(repository.account.first())
    }

    @Test
    fun `disconnect removes stored token and resets account`() = runTest {
        store.put(GitHubRepositoryImpl.KEY_TOKEN, "ghp_fake123")
        store.put(
            GitHubRepositoryImpl.KEY_ACCOUNT_JSON,
            """{"login":"octocat","name":"The Octocat","connectedAt":1000}"""
        )
        val repo = GitHubRepositoryImpl(store, TestDispatchers, OkHttpClient())
        assertNotNull(repo.account.first())

        val res = repo.disconnect()
        assertTrue(res.isSuccess)
        assertNull(repo.account.first())
        assertNull(store.get(GitHubRepositoryImpl.KEY_TOKEN))
    }

    @Test
    fun `all gitHubTools are instantiated and require GITHUB capability`() {
        val tools = GitHubConnectorTools.all(repository)
        assertEquals(8, tools.size)
        tools.forEach { tool ->
            assertTrue(tool.requiredCapabilities.contains(com.neuron.ai.core.permissions.Capability.GITHUB))
        }
    }

    @Test
    fun `startDeviceFlow parses device code response correctly`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_code":"dev_xyz","user_code":"1234-ABCD","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""")
        )

        val customRepo = GitHubRepositoryImpl(
            credentials = store,
            dispatchers = TestDispatchers,
            httpClient = OkHttpClient(),
            authBaseUrl = server.url("/").toString()
        )

        val res = customRepo.startDeviceFlow()
        assertTrue(res.isSuccess)
        val deviceCode = res.getOrThrow()
        assertEquals("dev_xyz", deviceCode.deviceCode)
        assertEquals("1234-ABCD", deviceCode.userCode)
        assertEquals("https://github.com/login/device", deviceCode.verificationUri)
        assertEquals(900, deviceCode.expiresIn)
        assertEquals(5, deviceCode.interval)
    }
}
