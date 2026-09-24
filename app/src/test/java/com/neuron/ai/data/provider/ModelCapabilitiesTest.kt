package com.neuron.ai.data.provider

import com.neuron.ai.core.conversation.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 3 multimodal capability routing tests. */
class ModelCapabilitiesTest {

    private fun image(id: String = "att-1") = Attachment(
        id = id, displayName = "photo.jpg", mimeType = "image/jpeg",
        sizeBytes = 100, localPath = "attachments/$id.jpg", kind = Attachment.Kind.IMAGE
    )

    @Test
    fun `vision patterns match mainstream vision models`() {
        assertTrue(ModelCapabilities.estimateVision("gpt-4o", visionEnabled = false))
        assertTrue(ModelCapabilities.estimateVision("gemini-2.0-flash", visionEnabled = false))
        assertTrue(ModelCapabilities.estimateVision("claude-3-5-sonnet", visionEnabled = false))
        assertTrue(!ModelCapabilities.estimateVision("llama-3.1-8b-instruct", visionEnabled = false))
    }

    @Test
    fun `provider vision flag overrides estimation`() {
        assertTrue(ModelCapabilities.estimateVision("llama-3.1-8b-instruct", visionEnabled = true))
    }

    @Test
    fun `image with non-vision model is rejected with guidance`() {
        val model = ModelCapabilities.estimate("llama-3.1-8b-instruct", false, false)
        val error = ModelCapabilities.validateInput(model, listOf(image()))
        assertNotNull(error)
        assertTrue(error!!.contains("does not support images"))
        assertTrue(error.contains("llama-3.1-8b-instruct"))
    }

    @Test
    fun `image with vision model passes`() {
        val model = ModelCapabilities.estimate("gpt-4o", false, false)
        assertNull(ModelCapabilities.validateInput(model, listOf(image())))
    }

    @Test
    fun `text-only sends always pass`() {
        val model = ModelCapabilities.estimate("llama-3.1-8b-instruct", false, false)
        assertNull(ModelCapabilities.validateInput(model, emptyList()))
    }

    @Test
    fun `estimate builds capability-aware model`() {
        val model = ModelCapabilities.estimate("gpt-4o", false, false)
        assertEquals("gpt-4o", model.id)
        assertTrue(model.supportsVision)
    }
}
