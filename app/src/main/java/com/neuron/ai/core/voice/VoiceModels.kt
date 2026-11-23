package com.neuron.ai.core.voice

/**
 * Speech-To-Text (Voice input) engine selection.
 */
enum class SttEngineType {
    /** Built-in Android SpeechRecognizer (Google Speech Services / on-device OS recognizer). */
    SYSTEM,
    /** On-device AI Model (Whisper GGUF). Fully offline & private. */
    AI_MODEL;

    val label: String
        get() = when (this) {
            SYSTEM -> "System Recognizer (Default)"
            AI_MODEL -> "Whisper AI Model (On-Device)"
        }

    companion object {
        fun fromNameOrDefault(name: String?): SttEngineType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Text-To-Speech (Read aloud) engine selection.
 */
enum class TtsEngineType {
    /** Built-in Android TextToSpeech engine. */
    SYSTEM,
    /** Neural AI Voice model (High-fidelity on-device voice synthesis). */
    AI_MODEL;

    val label: String
        get() = when (this) {
            SYSTEM -> "System Text-to-Speech (Default)"
            AI_MODEL -> "Neural AI Voice (High Quality)"
        }

    companion object {
        fun fromNameOrDefault(name: String?): TtsEngineType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

enum class VoiceModelCategory {
    STT,
    TTS
}

/**
 * Metadata for a downloadable voice model (Whisper STT or Neural TTS).
 */
data class VoiceModelDefinition(
    val id: String,
    val name: String,
    val category: VoiceModelCategory,
    val description: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val fileName: String,
    val fallbackUrl: String? = null
) {
    val formattedSize: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return String.format(java.util.Locale.US, "%.1f MB", mb)
        }

    companion object {
        val PRESET_MODELS = listOf(
            VoiceModelDefinition(
                id = "whisper-tiny-gguf",
                name = "Whisper Tiny (Multilingual)",
                category = VoiceModelCategory.STT,
                description = "Ultra-fast neural speech recognition. Low RAM (~75 MB), supports 99+ languages.",
                sizeBytes = 77_700_000L,
                downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
                fileName = "ggml-whisper-tiny.bin",
                fallbackUrl = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
            ),
            VoiceModelDefinition(
                id = "whisper-base-gguf",
                name = "Whisper Base (Multilingual)",
                category = VoiceModelCategory.STT,
                description = "Higher accuracy on technical terms and accented speech (~145 MB).",
                sizeBytes = 148_000_000L,
                downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
                fileName = "ggml-whisper-base.bin",
                fallbackUrl = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
            ),
            VoiceModelDefinition(
                id = "kokoro-tts-82m",
                name = "Kokoro-82M Voice Pack",
                category = VoiceModelCategory.TTS,
                description = "Studio-quality neural text-to-speech. Natural prosody and warm expression (~88 MB).",
                sizeBytes = 92_360_543L,
                downloadUrl = "https://huggingface.co/onnx-community/Kokoro-82M-ONNX/resolve/main/onnx/model_quantized.onnx",
                fileName = "kokoro-v0_19-quantized.onnx",
                fallbackUrl = "https://hf-mirror.com/onnx-community/Kokoro-82M-ONNX/resolve/main/onnx/model_quantized.onnx"
            ),
            VoiceModelDefinition(
                id = "piper-voice-en",
                name = "Piper Voice Pack (Natural)",
                category = VoiceModelCategory.TTS,
                description = "Crisp, low-latency neural speech synthesis (~63 MB).",
                sizeBytes = 63_000_000L,
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                fileName = "piper-lessac-medium.onnx",
                fallbackUrl = "https://hf-mirror.com/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx"
            )
        )
    }
}

/**
 * State of a voice model on disk / network.
 */
sealed interface VoiceModelStatus {
    data object NotDownloaded : VoiceModelStatus
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : VoiceModelStatus
    data object Downloaded : VoiceModelStatus
    data class Error(val message: String) : VoiceModelStatus
}

/**
 * Voice & speech configuration state.
 */
data class VoiceSettings(
    val sttEngine: SttEngineType = SttEngineType.SYSTEM,
    val ttsEngine: TtsEngineType = TtsEngineType.SYSTEM,
    val activeSttModelId: String = "whisper-tiny-gguf",
    val activeTtsModelId: String = "kokoro-tts-82m",
    val activeVoiceName: String? = null,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f
)

/**
 * Real-time karaoke speech progress emitted by TTS engine.
 */
data class TtsSpokenProgress(
    val utteranceId: String,
    val start: Int,
    val end: Int,
    val word: String,
    val wordOccurrence: Int = 0
)
