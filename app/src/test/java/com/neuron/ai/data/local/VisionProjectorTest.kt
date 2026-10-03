package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionProjectorTest {

    @Test
    fun `a model with no projector beside it stays text only`() {
        val files = listOf(
            "Qwen2.5-1.5b-instruct-q4_k_m-1a2b3c4d.gguf",
            "tinyllama-1.1b-chat-v1.0.Q4_K_M-9f8e7d6c.gguf"
        )
        assertNull(VisionProjector.findProjectorFor(files[0], files))
        assertTrue(!VisionProjector.hasProjector(files[0], files))
    }

    @Test
    fun `the matching mmproj is preferred over a projector of another model`() {
        val files = listOf(
            "gemma-3-4b-it-Q4_K_M.gguf",
            "gemma-3-4b-it-mmproj-f16.gguf",
            "mmproj-model-f16.gguf"
        )
        assertEquals(
            "gemma-3-4b-it-mmproj-f16.gguf",
            VisionProjector.findProjectorFor("gemma-3-4b-it-Q4_K_M.gguf", files)
        )
    }

    @Test
    fun `the llama.cpp default naming is used when nothing matches the base`() {
        val files = listOf("some-model-Q4_K_M.gguf", "mmproj-model-f16.gguf")
        assertEquals(
            "mmproj-model-f16.gguf",
            VisionProjector.findProjectorFor("some-model-Q4_K_M.gguf", files)
        )
    }

    @Test
    fun `case and quant suffix differences still match`() {
        // Imports get a random suffix appended, so the stored name is not the
        // upstream name — matching has to survive that.
        val files = listOf(
            "Qwen2.5-VL-3B-Instruct-Q4_K_M-4d5e6f7a.gguf",
            "Qwen2.5-VL-3B-Instruct-mmproj-f16-4d5e6f7a.gguf"
        )
        assertTrue(VisionProjector.hasProjector(files[0], files))
    }

    @Test
    fun `split projector shards and non-gguf files are never chosen`() {
        val files = listOf(
            "model-Q4_K_M.gguf",
            "mmproj-model-f16-00001-of-00002.gguf",
            "mmproj-model-f16-00002-of-00002.gguf",
            "mmproj-model-f16.gguf.zip"
        )
        assertNull(VisionProjector.findProjectorFor("model-Q4_K_M.gguf", files))
        assertTrue(VisionProjector.isProjectorFile("mmproj-model-f16.gguf"))
        assertTrue(!VisionProjector.isProjectorFile("model-Q4_K_M.gguf"))
    }
}
