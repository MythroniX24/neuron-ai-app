package com.neuron.ai.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.voice.SttEngineType
import com.neuron.ai.core.voice.VoiceModelDefinition
import com.neuron.ai.core.voice.VoiceSettingsRepository
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SpeechToTextManager(
    private val context: Context,
    private val settingsRepository: VoiceSettingsRepository,
    private val modelManager: VoiceModelManager,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            val settings = settingsRepository.voiceSettings.first()
            startListeningInternal(settings.sttEngine, onPartial, onFinal, onError)
        }
    }

    private fun startListeningInternal(
        engineType: SttEngineType,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            destroyRecognizer()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _isListening.value = false
                onError("Speech recognition service is not available on this device")
                return@post
            }

            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                        }

                        override fun onBeginningOfSpeech() {
                            _isListening.value = true
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.08f, 1f)
                            _audioLevel.value = normalized
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _isListening.value = false
                            _audioLevel.value = 0f
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            _audioLevel.value = 0f
                            destroyRecognizer()
                            val message = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Audio recording permission required"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                                SpeechRecognizer.ERROR_SERVER -> "Server error"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                                else -> "Speech recognition error ($error)"
                            }
                            logger?.w("SpeechToTextManager", "Speech recognition error: $message")
                            onError(message)
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            _audioLevel.value = 0f
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val recognizedText = matches?.firstOrNull()?.trim().orEmpty()
                            destroyRecognizer()
                            if (recognizedText.isNotBlank()) {
                                onFinal(recognizedText)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partialText = matches?.firstOrNull()?.trim().orEmpty()
                            if (partialText.isNotBlank()) {
                                onPartial(partialText)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    if (engineType == SttEngineType.AI_MODEL) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }

                recognizer?.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                _isListening.value = false
                destroyRecognizer()
                logger?.w("SpeechToTextManager", "Failed to start listening: ${e.message}", e)
                onError("Failed to start voice input: ${e.message}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            destroyRecognizer()
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            logger?.w("SpeechToTextManager", "Error stopping recognizer: ${e.message}", e)
        } finally {
            recognizer = null
            _isListening.value = false
            _audioLevel.value = 0f
        }
    }
}
