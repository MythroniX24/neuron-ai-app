package com.neuron.ai.core.voice

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface VoiceSettingsRepository {
    val voiceSettings: Flow<VoiceSettings>
    suspend fun setSttEngine(engine: SttEngineType)
    suspend fun setTtsEngine(engine: TtsEngineType)
    suspend fun setActiveSttModelId(modelId: String)
    suspend fun setActiveTtsModelId(modelId: String)
    suspend fun setSpeechRate(rate: Float)
    suspend fun setSpeechPitch(pitch: Float)
}

private val Context.voiceDataStore by preferencesDataStore(name = "neuron_voice_settings")

class VoiceSettingsRepositoryImpl(
    context: Context,
    @Suppress("UNUSED_PARAMETER") dispatchers: DispatcherProvider
) : VoiceSettingsRepository {

    private val dataStore = context.applicationContext.voiceDataStore

    override val voiceSettings: Flow<VoiceSettings> = dataStore.data.map { prefs ->
        VoiceSettings(
            sttEngine = SttEngineType.fromNameOrDefault(prefs[KEY_STT_ENGINE]),
            ttsEngine = TtsEngineType.fromNameOrDefault(prefs[KEY_TTS_ENGINE]),
            activeSttModelId = prefs[KEY_ACTIVE_STT_MODEL] ?: "whisper-tiny-gguf",
            activeTtsModelId = prefs[KEY_ACTIVE_TTS_MODEL] ?: "kokoro-tts-82m",
            speechRate = prefs[KEY_SPEECH_RATE] ?: 1.0f,
            speechPitch = prefs[KEY_SPEECH_PITCH] ?: 1.0f
        )
    }

    override suspend fun setSttEngine(engine: SttEngineType) {
        dataStore.edit { prefs -> prefs[KEY_STT_ENGINE] = engine.name }
    }

    override suspend fun setTtsEngine(engine: TtsEngineType) {
        dataStore.edit { prefs -> prefs[KEY_TTS_ENGINE] = engine.name }
    }

    override suspend fun setActiveSttModelId(modelId: String) {
        dataStore.edit { prefs -> prefs[KEY_ACTIVE_STT_MODEL] = modelId }
    }

    override suspend fun setActiveTtsModelId(modelId: String) {
        dataStore.edit { prefs -> prefs[KEY_ACTIVE_TTS_MODEL] = modelId }
    }

    override suspend fun setSpeechRate(rate: Float) {
        dataStore.edit { prefs -> prefs[KEY_SPEECH_RATE] = rate }
    }

    override suspend fun setSpeechPitch(pitch: Float) {
        dataStore.edit { prefs -> prefs[KEY_SPEECH_PITCH] = pitch }
    }

    companion object {
        val KEY_STT_ENGINE = stringPreferencesKey("voice_stt_engine")
        val KEY_TTS_ENGINE = stringPreferencesKey("voice_tts_engine")
        val KEY_ACTIVE_STT_MODEL = stringPreferencesKey("voice_active_stt_model")
        val KEY_ACTIVE_TTS_MODEL = stringPreferencesKey("voice_active_tts_model")
        val KEY_SPEECH_RATE = floatPreferencesKey("voice_speech_rate")
        val KEY_SPEECH_PITCH = floatPreferencesKey("voice_speech_pitch")
    }
}
