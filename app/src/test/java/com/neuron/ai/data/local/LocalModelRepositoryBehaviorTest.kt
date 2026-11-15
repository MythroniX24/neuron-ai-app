package com.neuron.ai.data.local

import com.neuron.ai.TestDispatchers
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelRepositoryBehaviorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir by lazy { tmp.newFolder("files") }

    private fun repository(): LocalModelRepository =
        LocalModelRepository(
            filesDir = filesDir,
            dispatchers = TestDispatchers,
            logger = null
        )

    private suspend fun importTinyModel(repo: LocalModelRepository): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("gguf/real-tinyllama-header.bin")
        assertNotNull("test fixture missing", stream)
        val source = tmp.newFile("behavior-${System.nanoTime()}.gguf")
        source.writeBytes(stream!!.use { it.readBytes() })
        repo.importFile(source, "tinyllama-behavior.Q4_K_M.gguf").getOrThrow().id
    }

    @Test
    fun `behavior options persist across repository restarts`() = runTest {
        val repo = repository()
        val id = importTinyModel(repo)

        repo.updateBehaviorOptions(
            modelId = id,
            customInstructions = "  Answer in Hindi with short bullet points.  ",
            replyStyle = LocalReplyStyle.CONCISE.name
        )

        val stored = repo.models.value.single { it.id == id }
        assertEquals("Answer in Hindi with short bullet points.", stored.customInstructions)
        assertEquals(LocalReplyStyle.CONCISE.name, stored.replyStyle)

        val reloaded = repository().models.value.single { it.id == id }
        assertEquals("Answer in Hindi with short bullet points.", reloaded.customInstructions)
        assertEquals(LocalReplyStyle.CONCISE.name, reloaded.replyStyle)
        assertTrue(File(filesDir, "local-models.json").exists())
    }

    @Test
    fun `invalid style falls back and instructions are capped`() = runTest {
        val repo = repository()
        val id = importTinyModel(repo)

        repo.updateBehaviorOptions(
            modelId = id,
            customInstructions = "x".repeat(LocalPromptOptions.MAX_INSTRUCTIONS_CHARS + 250),
            replyStyle = "UNKNOWN_STYLE"
        )

        val stored = repo.models.value.single { it.id == id }
        assertEquals(LocalReplyStyle.BALANCED.name, stored.replyStyle)
        assertEquals(LocalPromptOptions.MAX_INSTRUCTIONS_CHARS, stored.customInstructions.length)
    }

    @Test
    fun `updating behavior does not touch another model`() = runTest {
        val repo = repository()
        val first = importTinyModel(repo)

        repo.updateBehaviorOptions(first, "Be brief.", LocalReplyStyle.CONCISE.name)

        val others = repo.models.value.filterNot { it.id == first }
        assertTrue(others.all { it.customInstructions.isEmpty() })
    }
}
