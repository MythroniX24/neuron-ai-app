package com.neuron.ai.core.voice

import com.neuron.ai.data.voice.TextToSpeechManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelsTest {

    @Test
    fun testSttEngineTypeMapping() {
        assertEquals(SttEngineType.SYSTEM, SttEngineType.fromNameOrDefault("SYSTEM"))
        assertEquals(SttEngineType.AI_MODEL, SttEngineType.fromNameOrDefault("AI_MODEL"))
        assertEquals(SttEngineType.AI_MODEL, SttEngineType.fromNameOrDefault("ai_model"))
        assertEquals(SttEngineType.SYSTEM, SttEngineType.fromNameOrDefault(null))
        assertEquals(SttEngineType.SYSTEM, SttEngineType.fromNameOrDefault("UNKNOWN"))
    }

    @Test
    fun testTtsEngineTypeMapping() {
        assertEquals(TtsEngineType.SYSTEM, TtsEngineType.fromNameOrDefault("SYSTEM"))
        assertEquals(TtsEngineType.AI_MODEL, TtsEngineType.fromNameOrDefault("AI_MODEL"))
        assertEquals(TtsEngineType.AI_MODEL, TtsEngineType.fromNameOrDefault("ai_model"))
        assertEquals(TtsEngineType.SYSTEM, TtsEngineType.fromNameOrDefault(null))
        assertEquals(TtsEngineType.SYSTEM, TtsEngineType.fromNameOrDefault("INVALID"))
    }

    @Test
    fun testPresetVoiceModels() {
        val models = VoiceModelDefinition.PRESET_MODELS
        assertTrue("Preset voice models should not be empty", models.isNotEmpty())

        val sttModels = models.filter { it.category == VoiceModelCategory.STT }
        val ttsModels = models.filter { it.category == VoiceModelCategory.TTS }

        assertTrue("Should have at least one STT model", sttModels.isNotEmpty())
        assertTrue("Should have at least one TTS model", ttsModels.isNotEmpty())

        models.forEach { model ->
            assertTrue("Model id must not be blank", model.id.isNotBlank())
            assertTrue("Model name must not be blank", model.name.isNotBlank())
            assertTrue("Model file name must not be blank", model.fileName.isNotBlank())
            assertTrue("Model downloadUrl must be valid https URL", model.downloadUrl.startsWith("https://"))
            assertTrue("Model size must be positive", model.sizeBytes > 0)
            assertNotNull("Formatted size must not be null", model.formattedSize)
        }
    }

    @Test
    fun testCleanMarkdownForSpeech() {
        val markdown = """
            # Header Title
            Here is **bold** and *italic* text.
            Here is a [Google Link](https://google.com).
            ```python
            def foo():
                print("hidden code")
            ```
            And inline `code_block`.
            Final text.
        """.trimIndent()

        val clean = TextToSpeechManager.cleanMarkdownForSpeech(markdown)

        assertTrue("Code block should be stripped", !clean.contains("hidden code"))
        assertTrue("Inline code should be stripped", !clean.contains("code_block"))
        assertTrue("Links should only keep link label", clean.contains("Google Link"))
        assertTrue("Link URL should not be in clean text", !clean.contains("https://google.com"))
        assertTrue("Markdown asterisks should be stripped", !clean.contains("**") && !clean.contains("*italic*"))
        assertTrue("Bold text should remain without asterisks", clean.contains("bold"))
        assertTrue("Italic text should remain without asterisks", clean.contains("italic"))
        assertTrue("Header text should remain without hash", clean.contains("Header Title") && !clean.contains("#"))
    }

    @Test
    fun testTtsSpokenProgress() {
        val progress = TtsSpokenProgress(
            utteranceId = "msg-123",
            start = 5,
            end = 10,
            word = "Hello"
        )
        assertEquals("msg-123", progress.utteranceId)
        assertEquals(5, progress.start)
        assertEquals(10, progress.end)
        assertEquals("Hello", progress.word)
    }
}
