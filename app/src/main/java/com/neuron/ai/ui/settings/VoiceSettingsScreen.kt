package com.neuron.ai.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.voice.SttEngineType
import com.neuron.ai.core.voice.TtsEngineType
import com.neuron.ai.core.voice.VoiceModelCategory
import com.neuron.ai.core.voice.VoiceModelDefinition
import com.neuron.ai.core.voice.VoiceModelStatus
import com.neuron.ai.core.voice.VoiceSettings
import com.neuron.ai.di.AppContainer
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: VoiceSettingsViewModel = viewModel(
        factory = VoiceSettingsViewModelFactory(container)
    )

    val settings by viewModel.voiceSettings.collectAsStateWithLifecycle()
    val modelStatuses by viewModel.modelStatuses.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()

    val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle(
        initialValue = com.neuron.ai.core.settings.ThemeMode.SYSTEM
    )
    val isDark = when (themeMode) {
        com.neuron.ai.core.settings.ThemeMode.LIGHT -> false
        com.neuron.ai.core.settings.ThemeMode.DARK -> true
        com.neuron.ai.core.settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val screenBg = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF6F7F9)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFFFFFF)
    val cardBorderColor = if (isDark) Color(0xFF2A2F37) else Color(0xFFE5E7EB)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Voice & Speech",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = screenBg,
                scrolledContainerColor = screenBg
            )
        )

        HorizontalDivider(
            color = cardBorderColor.copy(alpha = 0.6f),
            thickness = 0.8.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // =====================================================================
            // Section 1: Speech-To-Text (STT) - Voice Input
            // =====================================================================
            VoiceSectionHeader(
                icon = Icons.Outlined.Mic,
                title = "Speech to Text (Voice Input)",
                subtitle = "Select whether to use the Android built-in speech recognizer or download an offline AI Whisper model."
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorderColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Voice Input Engine",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))

                    EngineOptionRow(
                        title = "System Speech Recognizer",
                        subtitle = "Android default recognition. Instant, battery-efficient, no download needed.",
                        selected = settings.sttEngine == SttEngineType.SYSTEM,
                        onSelect = { viewModel.setSttEngine(SttEngineType.SYSTEM) }
                    )

                    Spacer(Modifier.height(8.dp))

                    EngineOptionRow(
                        title = "Whisper AI Model",
                        subtitle = "On-device neural speech recognition. High accuracy, private, works offline.",
                        selected = settings.sttEngine == SttEngineType.AI_MODEL,
                        onSelect = { viewModel.setSttEngine(SttEngineType.AI_MODEL) }
                    )
                }
            }

            Text(
                text = "Downloadable STT AI Models",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            VoiceModelDefinition.PRESET_MODELS.filter { it.category == VoiceModelCategory.STT }.forEach { model ->
                VoiceModelDownloadCard(
                    model = model,
                    status = modelStatuses[model.id] ?: VoiceModelStatus.NotDownloaded,
                    isActive = settings.sttEngine == SttEngineType.AI_MODEL && settings.activeSttModelId == model.id,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    onDownload = { viewModel.downloadModel(model) },
                    onCancel = { viewModel.cancelDownload(model) },
                    onDelete = { viewModel.deleteModel(model) },
                    onSelectActive = {
                        viewModel.setActiveSttModel(model.id)
                        viewModel.setSttEngine(SttEngineType.AI_MODEL)
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // =====================================================================
            // Section 2: Text-To-Speech (TTS) - Read Aloud
            // =====================================================================
            VoiceSectionHeader(
                icon = Icons.Outlined.VolumeUp,
                title = "Text to Speech (Read Aloud)",
                subtitle = "Choose your TTS engine to read AI responses aloud, or download neural voice models."
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorderColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Read Aloud Engine",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))

                    EngineOptionRow(
                        title = "System Text-to-Speech",
                        subtitle = "Uses Android's installed TTS voices (Google / Samsung / OEM).",
                        selected = settings.ttsEngine == TtsEngineType.SYSTEM,
                        onSelect = { viewModel.setTtsEngine(TtsEngineType.SYSTEM) }
                    )

                    Spacer(Modifier.height(8.dp))

                    EngineOptionRow(
                        title = "Neural AI Voice Model",
                        subtitle = "High-fidelity on-device voice synthesis with natural human prosody.",
                        selected = settings.ttsEngine == TtsEngineType.AI_MODEL,
                        onSelect = { viewModel.setTtsEngine(TtsEngineType.AI_MODEL) }
                    )

                    if (settings.ttsEngine == TtsEngineType.AI_MODEL) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (settings.activeTtsModelId == "kokoro-tts-82m")
                                        "Kokoro Studio Neural Female Persona Active"
                                    else
                                        "Piper Natural Crisp Male Persona Active",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        val availableVoices = remember { viewModel.getAvailableVoices() }
                        if (availableVoices.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Installed Device Voice",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    androidx.compose.material3.FilterChip(
                                        selected = settings.activeVoiceName == null,
                                        onClick = { viewModel.setActiveVoiceName(null) },
                                        label = { Text("Default") }
                                    )
                                }
                                items(availableVoices.take(8).size) { idx ->
                                    val v = availableVoices[idx]
                                    val isSelected = settings.activeVoiceName == v.name
                                    val displayLabel = v.name.substringAfterLast("-").ifBlank { v.name.takeLast(8) }
                                    androidx.compose.material3.FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.setActiveVoiceName(v.name) },
                                        label = { Text(displayLabel) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = cardBorderColor.copy(alpha = 0.5f), thickness = 0.8.dp)
                    Spacer(Modifier.height(16.dp))

                    // Speech Rate Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speech Rate",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", settings.speechRate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.speechRate,
                        onValueChange = { viewModel.setSpeechRate(it) },
                        valueRange = 0.75f..1.5f,
                        steps = 5
                    )

                    Spacer(Modifier.height(8.dp))

                    // Speech Pitch Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Voice Pitch",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", settings.speechPitch),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.speechPitch,
                        onValueChange = { viewModel.setSpeechPitch(it) },
                        valueRange = 0.8f..1.2f,
                        steps = 4
                    )

                    Spacer(Modifier.height(12.dp))

                    // Test Voice Button
                    Button(
                        onClick = { viewModel.testVoice() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSpeaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isSpeaking) "Stop Speaking" else "Test Voice Playback")
                    }
                }
            }

            Text(
                text = "Downloadable TTS AI Voice Models",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            VoiceModelDefinition.PRESET_MODELS.filter { it.category == VoiceModelCategory.TTS }.forEach { model ->
                VoiceModelDownloadCard(
                    model = model,
                    status = modelStatuses[model.id] ?: VoiceModelStatus.NotDownloaded,
                    isActive = settings.ttsEngine == TtsEngineType.AI_MODEL && settings.activeTtsModelId == model.id,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    onDownload = { viewModel.downloadModel(model) },
                    onCancel = { viewModel.cancelDownload(model) },
                    onDelete = { viewModel.deleteModel(model) },
                    onSelectActive = {
                        viewModel.setActiveTtsModel(model.id)
                        viewModel.setTtsEngine(TtsEngineType.AI_MODEL)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VoiceSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EngineOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VoiceModelDownloadCard(
    model: VoiceModelDefinition,
    status: VoiceModelStatus,
    isActive: Boolean,
    cardBg: Color,
    cardBorderColor: Color,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelectActive: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else cardBorderColor
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${model.formattedSize} • ${model.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (status) {
                is VoiceModelStatus.NotDownloaded -> {
                    FilledTonalButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Download Model (${model.formattedSize})")
                    }
                }

                is VoiceModelStatus.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Downloading… ${status.progressPercent}%",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { status.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }

                is VoiceModelStatus.Downloaded -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isActive) {
                            Button(
                                onClick = onSelectActive,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Use This Model")
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "In Use (Active Profile)",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete model",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                is VoiceModelStatus.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Error: ${status.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        FilledTonalButton(
                            onClick = onDownload,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retry Download")
                        }
                    }
                }
            }
        }
    }
}
