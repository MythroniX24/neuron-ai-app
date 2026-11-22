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
            word = "Hello",
            wordOccurrence = 2
        )
        assertEquals("msg-123", progress.utteranceId)
        assertEquals(5, progress.start)
        assertEquals(10, progress.end)
        assertEquals("Hello", progress.word)
        assertEquals(2, progress.wordOccurrence)
    }

    @Test
    fun testVoiceSettingsDefaultsAndActiveVoice() {
        val defaultSettings = VoiceSettings()
        assertEquals(SttEngineType.SYSTEM, defaultSettings.sttEngine)
        assertEquals(TtsEngineType.SYSTEM, defaultSettings.ttsEngine)
        assertEquals("whisper-tiny-gguf", defaultSettings.activeSttModelId)
        assertEquals("kokoro-tts-82m", defaultSettings.activeTtsModelId)
        assertEquals(null, defaultSettings.activeVoiceName)
        assertEquals(1.0f, defaultSettings.speechRate)
        assertEquals(1.0f, defaultSettings.speechPitch)

        val updated = defaultSettings.copy(
            ttsEngine = TtsEngineType.AI_MODEL,
            activeVoiceName = "en-us-x-sfg-network",
            speechRate = 1.15f
        )
        assertEquals(TtsEngineType.AI_MODEL, updated.ttsEngine)
        assertEquals("en-us-x-sfg-network", updated.activeVoiceName)
        assertEquals(1.15f, updated.speechRate)
    }

    @Test
    fun testKokoroModelUrlIntegrity() {
        val kokoro = VoiceModelDefinition.PRESET_MODELS.first { it.id == "kokoro-tts-82m" }
        assertEquals("Kokoro-82M Voice Pack", kokoro.name)
        assertEquals(VoiceModelCategory.TTS, kokoro.category)
        assertTrue(kokoro.downloadUrl.contains("onnx-community/Kokoro-82M-ONNX"))
        assertTrue(kokoro.downloadUrl.endsWith("model_quantized.onnx"))
        assertTrue(kokoro.sizeBytes > 90_000_000L)
    }

    @Test
    fun testOccurrenceIndexCalculation() {
        val text = "What is what? That is what we know."
        val word = "what"
        val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")

        val matches = pattern.findAll(text).toList()
        assertEquals(3, matches.size)

        // 1st occurrence ("What")
        val firstStart = matches[0].range.first
        val firstPrior = text.substring(0, firstStart)
        val firstOcc = pattern.findAll(firstPrior).count()
        assertEquals(0, firstOcc)

        // 2nd occurrence ("what")
        val secondStart = matches[1].range.first
        val secondPrior = text.substring(0, secondStart)
        val secondOcc = pattern.findAll(secondPrior).count()
        assertEquals(1, secondOcc)

        // 3rd occurrence ("what")
        val thirdStart = matches[2].range.first
        val thirdPrior = text.substring(0, thirdStart)
        val thirdOcc = pattern.findAll(thirdPrior).count()
        assertEquals(2, thirdOcc)
    }
}
