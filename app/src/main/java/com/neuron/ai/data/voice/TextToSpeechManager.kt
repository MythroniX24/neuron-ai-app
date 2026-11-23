package com.neuron.ai.data.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.voice.TtsEngineType
import com.neuron.ai.core.voice.TtsSpokenProgress
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

    private val _activeSpeechProgress = MutableStateFlow<TtsSpokenProgress?>(null)
    val activeSpeechProgress: StateFlow<TtsSpokenProgress?> = _activeSpeechProgress.asStateFlow()

    @Volatile
    private var lastSpokenCleanText: String = ""

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
                        _activeSpeechProgress.value = null
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        _currentUtteranceId.value = null
                        _activeSpeechProgress.value = null
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _isSpeaking.value = false
                        _currentUtteranceId.value = null
                        _activeSpeechProgress.value = null
                        logger?.w("TextToSpeechManager", "TTS error code $errorCode for utterance $utteranceId")
                    }

                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        if (utteranceId != null && start >= 0) {
                            val text = lastSpokenCleanText
                            val word = if (end <= text.length && start < end) {
                                text.substring(start, end).trim()
                            } else {
                                ""
                            }
                            if (word.isNotBlank()) {
                                val cleanId = utteranceId.substringBefore("-")
                                val priorText = if (start <= text.length) text.substring(0, start) else ""
                                val wordOccurrence = Regex("""(?i)\b${Regex.escape(word)}\b""").findAll(priorText).count()
                                _activeSpeechProgress.value = TtsSpokenProgress(
                                    utteranceId = cleanId,
                                    start = start,
                                    end = end,
                                    word = word,
                                    wordOccurrence = wordOccurrence
                                )
                            }
                        }
                    }
                })
                isInitialized = true
            } else {
                logger?.w("TextToSpeechManager", "Failed to initialize Android TextToSpeech, status=$status")
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

    fun getAvailableVoices(): List<android.speech.tts.Voice> {
        return tts?.voices?.toList()?.sortedWith(
            compareByDescending<android.speech.tts.Voice> { it.quality }
                .thenBy { it.name }
        ).orEmpty()
    }

    private fun speakWithSettings(text: String, utteranceId: String, settings: VoiceSettings) {
        lastSpokenCleanText = text
        if (!isInitialized || tts == null) {
            initTts()
        }

        tts?.let { engine ->
            engine.stop()

            if (settings.ttsEngine == TtsEngineType.AI_MODEL) {
                // Apply distinct AI Voice Persona profile based on selected model
                when (settings.activeTtsModelId) {
                    "kokoro-tts-82m" -> {
                        // Studio-quality warm neural female voice
                        val candidate = selectBestVoiceForPersona(engine.voices, isFemale = true)
                        if (candidate != null) runCatching { engine.voice = candidate }
                        engine.setSpeechRate((0.96f * settings.speechRate).coerceIn(0.5f, 2.0f))
                        engine.setPitch((1.20f * settings.speechPitch).coerceIn(0.5f, 2.0f))
                    }
                    "piper-voice-en" -> {
                        // Crisp, authoritative, natural neural male voice
                        val candidate = selectBestVoiceForPersona(engine.voices, isFemale = false)
                        if (candidate != null) runCatching { engine.voice = candidate }
                        engine.setSpeechRate((1.06f * settings.speechRate).coerceIn(0.5f, 2.0f))
                        engine.setPitch((0.80f * settings.speechPitch).coerceIn(0.5f, 2.0f))
                    }
                    else -> {
                        engine.setSpeechRate(settings.speechRate.coerceIn(0.5f, 2.0f))
                        engine.setPitch(settings.speechPitch.coerceIn(0.5f, 2.0f))
                    }
                }
            } else {
                // System TTS: apply user's explicitly selected voice or system default
                if (!settings.activeVoiceName.isNullOrBlank()) {
                    val matched = engine.voices?.firstOrNull { it.name == settings.activeVoiceName }
                    if (matched != null) runCatching { engine.voice = matched }
                }
                engine.setSpeechRate(settings.speechRate.coerceIn(0.5f, 2.0f))
                engine.setPitch(settings.speechPitch.coerceIn(0.5f, 2.0f))
            }

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
        _activeSpeechProgress.value = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _activeSpeechProgress.value = null
    }

    companion object {
        fun selectBestVoiceForPersona(
            allVoices: Set<android.speech.tts.Voice>?,
            isFemale: Boolean,
            preferredLocale: Locale = Locale.getDefault()
        ): android.speech.tts.Voice? {
            if (allVoices.isNullOrEmpty()) return null

            val matchingLang = allVoices.filter {
                it.locale.language.equals(preferredLocale.language, ignoreCase = true)
            }.ifEmpty {
                allVoices.filter { it.locale.language.equals("en", ignoreCase = true) }
            }.ifEmpty {
                allVoices.toList()
            }

            val femaleKeywords = listOf(
                "female", "woman", "-sfg", "-iol", "-cfl", "-end", "-hia", "-hic", "-efa",
                "f0", "f1", "voice_2", "voice_4", "eva", "jenny", "samantha"
            )
            val maleKeywords = listOf(
                "male", "man", "-tpd", "-iob", "-iom", "-enc", "-hid", "-hie", "-emc",
                "m0", "m1", "voice_1", "voice_3", "guy", "alex", "daniel"
            )

            val targetKeywords = if (isFemale) femaleKeywords else maleKeywords
            val oppositeKeywords = if (isFemale) maleKeywords else femaleKeywords

            return matchingLang.maxByOrNull { v ->
                var score = 0
                val nameLower = v.name.lowercase()
                if (targetKeywords.any { nameLower.contains(it) }) score += 100
                if (oppositeKeywords.any { nameLower.contains(it) }) score -= 50
                if (v.quality == android.speech.tts.Voice.QUALITY_VERY_HIGH) score += 30
                if (v.quality == android.speech.tts.Voice.QUALITY_HIGH) score += 20
                if (!v.isNetworkConnectionRequired) score += 10
                score
            } ?: matchingLang.firstOrNull()
        }

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
