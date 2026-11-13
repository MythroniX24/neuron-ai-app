package com.neuron.ai.data.local

import com.neuron.ai.TestDispatchers
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-level tests for the local model import path. The repository's import
 * logic needs no Android framework objects, so a temporary filesystem root is
 * enough — this runs in the normal `test` task in GitHub CI.
 */
class LocalModelRepositoryImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): LocalModelRepository =
        LocalModelRepository(
            filesDir = tmp.newFolder("files"),
            dispatchers = TestDispatchers,
            logger = null
        )

    /** Real GGUF header bytes (no general.quantization; the name carries it). */
    private fun realGgufBytes(): ByteArray {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("gguf/real-tinyllama-header.bin")
        assertNotNull("test fixture missing", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun writeRealGguf(name: String): File {
        val file = tmp.newFile(name)
        file.writeBytes(realGgufBytes())
        return file
    }

    @Test
    fun `importFile preserves the display name and registers the model`() = runTest {
        val repo = repository()
        val source = writeRealGguf("picked.gguf")
        val originalName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"

        val result = repo.importFile(source, originalName)

        assertTrue(result.isSuccess)
        val record = result.getOrThrow()
        assertEquals(originalName, record.displayName)
        assertEquals("Q4_K_M", record.quantization)
        assertEquals("llama", record.architecture)
        assertEquals(2048L, record.contextLength)
        assertEquals(22L, record.blockCount)
        assertEquals(LocalModelRecord.SOURCE_IMPORT, record.source)
        assertEquals(listOf(record), repo.models.value)

        // The model really landed inside the isolated models directory.
        val stored = File(repo.modelsDir, record.fileName)
        assertTrue(stored.exists())
        assertEquals(source.length(), stored.length())
        assertFalse(record.fileName.contains(".."))
        assertTrue(File(tmp.root, "files/local-models.json").exists())
    }

    @Test
    fun `importFile rejects a non-gguf file without registering it`() = runTest {
        val repo = repository()
        val fake = tmp.newFile("not-a-model.gguf")
        fake.writeBytes(ByteArray(64) { 'X'.code.toByte() })

        val result = repo.importFile(fake, "not-a-model.gguf")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GgufReader.InvalidGgufException)
        assertTrue(repo.models.value.isEmpty())
        assertEquals(0, repo.modelsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `importFile copies a projector as a companion, not as a model`() = runTest {
        val repo = repository()
        val source = writeRealGguf("picked-projector.gguf")

        val result = repo.importFile(source, "mmproj-model-f16.gguf")

        assertTrue(result.isSuccess)
        assertTrue(repo.models.value.isEmpty())
        assertTrue(File(repo.modelsDir, result.getOrThrow().fileName).exists())
    }
}
