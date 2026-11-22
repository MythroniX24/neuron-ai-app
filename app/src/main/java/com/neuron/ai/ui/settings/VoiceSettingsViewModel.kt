package com.neuron.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.voice.SttEngineType
import com.neuron.ai.core.voice.TtsEngineType
import com.neuron.ai.core.voice.VoiceModelDefinition
import com.neuron.ai.core.voice.VoiceModelStatus
import com.neuron.ai.core.voice.VoiceSettings
import com.neuron.ai.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VoiceSettingsViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val voiceRepo = container.voiceSettingsRepository
    val modelManager = container.voiceModelManager
    val ttsManager = container.textToSpeechManager

    val voiceSettings: StateFlow<VoiceSettings> = voiceRepo.voiceSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VoiceSettings()
    )

    val modelStatuses: StateFlow<Map<String, VoiceModelStatus>> = modelManager.statusMap

    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    fun setSttEngine(engine: SttEngineType) {
        viewModelScope.launch {
            voiceRepo.setSttEngine(engine)
        }
    }

    fun setTtsEngine(engine: TtsEngineType) {
        viewModelScope.launch {
            voiceRepo.setTtsEngine(engine)
        }
    }

    fun setActiveSttModel(modelId: String) {
        viewModelScope.launch {
            voiceRepo.setActiveSttModelId(modelId)
        }
    }

    fun setActiveTtsModel(modelId: String) {
        viewModelScope.launch {
            voiceRepo.setActiveTtsModelId(modelId)
        }
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch {
            voiceRepo.setSpeechRate(rate)
        }
    }

    fun setSpeechPitch(pitch: Float) {
        viewModelScope.launch {
            voiceRepo.setSpeechPitch(pitch)
        }
    }

    fun downloadModel(definition: VoiceModelDefinition) {
        modelManager.downloadModel(definition)
    }

    fun cancelDownload(definition: VoiceModelDefinition) {
        modelManager.cancelDownload(definition)
    }

    fun deleteModel(definition: VoiceModelDefinition) {
        modelManager.deleteModel(definition)
    }

    fun testVoice() {
        if (ttsManager.isSpeaking.value) {
            ttsManager.stop()
        } else {
            ttsManager.speak("Hello! This is Neuron AI. How can I help you today?")
        }
    }

    fun stopVoice() {
        ttsManager.stop()
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.stop()
    }
}

class VoiceSettingsViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VoiceSettingsViewModel(container) as T
    }
}
