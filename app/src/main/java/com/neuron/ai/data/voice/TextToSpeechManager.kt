package com.neuron.ai.data.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.voice.TtsEngineType
import com.neuron.ai.core.voice.VoiceModelDefinition
import com.neuron.ai.core.voice.VoiceSettings
import com.neuron.ai.core.voice.VoiceSettingsRepository
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TextToSpeechManager(
    private val context: Context,
    private val settingsRepository: VoiceSettingsRepository,
    private val modelManager: VoiceModelManager,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentUtteranceId = MutableStateFlow<String?>(null)
    val currentUtteranceId: StateFlow<String?> = _currentUtteranceId.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        _currentUtteranceId.value = utteranceId
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        _currentUtteranceId.value = null
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        _currentUtteranceId.value = null
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _isSpeaking.value = false
                        _currentUtteranceId.value = null
                        logger?.warn("TextToSpeechManager", "TTS error code $errorCode for utterance $utteranceId")
                    }
                })
                isInitialized = true
            } else {
                logger?.warn("TextToSpeechManager", "Failed to initialize Android TextToSpeech, status=$status")
            }
        }
    }

    fun speak(rawMarkdown: String, utteranceId: String = UUID.randomUUID().toString()) {
        val cleanText = cleanMarkdownForSpeech(rawMarkdown)
        if (cleanText.isBlank()) return

        scope.launch {
            val settings = settingsRepository.voiceSettings.first()
            speakWithSettings(cleanText, utteranceId, settings)
        }
    }

    private fun speakWithSettings(text: String, utteranceId: String, settings: VoiceSettings) {
        if (!isInitialized || tts == null) {
            initTts()
        }

        tts?.let { engine ->
            engine.stop()
            engine.setSpeechRate(settings.speechRate.coerceIn(0.5f, 2.0f))
            engine.setPitch(settings.speechPitch.coerceIn(0.5f, 2.0f))

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            _currentUtteranceId.value = utteranceId
            _isSpeaking.value = true

            // For very long texts, TextToSpeech has max input length per chunk. Split into sentences.
            val chunks = splitTextIntoChunks(text, 3500)
            if (chunks.isEmpty()) return

            engine.speak(chunks[0], TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            for (i in 1 until chunks.size) {
                val subId = "$utteranceId-$i"
                val subParams = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, subId)
                }
                engine.speak(chunks[i], TextToSpeech.QUEUE_ADD, subParams, subId)
            }
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _currentUtteranceId.value = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        fun cleanMarkdownForSpeech(raw: String): String {
            var text = raw
            // Remove code blocks (```...```)
            text = text.replace(Regex("```[\\s\\S]*?```"), " ")
            // Remove inline code (`...`)
            text = text.replace(Regex("`[^`]*`"), " ")
            // Remove image links ![alt](url)
            text = text.replace(Regex("!\\[[^\\]]*\\]\\([^\\)]*\\)"), " ")
            // Replace markdown links [text](url) -> text
            text = text.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
            // Remove html tags
            text = text.replace(Regex("<[^>]*>"), " ")
            // Remove headers #, ##, etc.
            text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")
            // Remove bold/italics/strikethrough markers
            text = text.replace(Regex("[*_~#]"), "")
            // Replace multiple whitespace/newlines with single space
            text = text.replace(Regex("\\s+"), " ").trim()
            return text
        }

        private fun splitTextIntoChunks(text: String, maxChunkSize: Int): List<String> {
            if (text.length <= maxChunkSize) return listOf(text)
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = (start + maxChunkSize).coerceAtMost(text.length)
                if (end < text.length) {
                    val periodIndex = text.lastIndexOf('.', end)
                    if (periodIndex > start + 100) {
                        end = periodIndex + 1
                    }
                }
                chunks.add(text.substring(start, end).trim())
                start = end
            }
            return chunks
        }
    }
}
