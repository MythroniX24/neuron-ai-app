package com.neuron.ai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.LocalTurnKind
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing

/**
 * Full-page editor for ONE on-device model. Replaces the cramped dialog that
 * used to hold routing + performance + actions: here the user can see the
 * model's GGUF facts, enable it for chat, choose which turn kinds it serves,
 * override answer length and context, load/unload/benchmark, and delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettingsScreen(
    container: AppContainer,
    modelId: String,
    onBack: () -> Unit
) {
    val viewModel: LocalAiViewModel = viewModel(factory = LocalAiViewModel.Factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val model = state.models.firstOrNull { it.id == modelId }

    if (model == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Spacing.lg)
        ) {
            Text("Model settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.md))
            Text(
                "This model is no longer available. It may have been deleted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onBack) { Text("Back to models") }
        }
        return
    }

    val routing = viewModel.routingRuleFor(model.id)
    val load = state.loadState
    val active = load is LocalLoadState.Ready && load.modelId == model.id
    val loading = load is LocalLoadState.Loading && load.modelId == model.id
    val visionReady = viewModel.visionReady(model)
    val defaultContext = (model.contextLength?.toInt() ?: 2048)
        .coerceIn(512, 4096)

    var contextValue by remember(model.id, model.contextLimit) {
        mutableFloatStateOf((model.contextLimit ?: defaultContext).toFloat())
    }
    var outputValue by remember(model.id, model.maxOutputTokens) {
        mutableFloatStateOf((model.maxOutputTokens ?: 1024).toFloat())
    }
    var threadValue by remember(model.id, state.cpuThreads) {
        mutableFloatStateOf(state.cpuThreads.toFloat())
    }
    var showDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Model settings", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            Modifier.padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // ---- Header ---------------------------------------------------
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        model.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        when {
                            active -> "Loaded and ready"
                            loading -> "Loading into memory…"
                            load is LocalLoadState.Failed && load.modelId == model.id ->
                                "Load failed — open the debug console for the engine log"
                            else -> "Not loaded"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- GGUF facts ----------------------------------------------
            SettingsSection("Model details")
            InfoRow("Size", viewModel.formatBytes(model.sizeBytes))
            InfoRow("Quantization", model.quantization ?: "Not declared")
            InfoRow("Architecture", model.architecture ?: "Not declared")
            InfoRow(
                "Declared context",
                model.contextLength?.let { "$it tokens" } ?: "Not declared"
            )
            InfoRow("Layers", model.blockCount?.toString() ?: "Not declared")
            InfoRow(
                "Vision projector",
                if (visionReady) "Available" else "Not imported"
            )
            InfoRow(
                "Tool template",
                if (model.supportsTools) "Declared by the GGUF" else "Not declared"
            )

            HorizontalDivider()

            // ---- Chat + routing ------------------------------------------
            SettingsSection("Use this model for")
            SettingSwitch(
                title = "Chat",
                subtitle = "Show this model in the top-bar model selector",
                checked = model.enabledForChat,
                onCheckedChange = { viewModel.setEnabled(model.id, it) }
            )
            SettingSwitch(
                title = "Text turns",
                subtitle = "Normal questions and conversation",
                checked = routing.text,
                onCheckedChange = {
                    viewModel.setRoutingRule(model.id, LocalTurnKind.TEXT, it)
                }
            )
            SettingSwitch(
                title = "Tool / agent turns",
                subtitle = if (model.supportsTools) {
                    "Use for terminal, browser and workspace actions"
                } else {
                    "The GGUF does not declare tool-calling support"
                },
                checked = routing.tools,
                enabled = model.supportsTools,
                onCheckedChange = {
                    viewModel.setRoutingRule(model.id, LocalTurnKind.TOOLS, it)
                }
            )
            SettingSwitch(
                title = "Vision turns",
                subtitle = if (visionReady) {
                    "Use for messages with images"
                } else {
                    "Import this model's mmproj projector first"
                },
                checked = routing.vision,
                enabled = visionReady,
                onCheckedChange = {
                    viewModel.setRoutingRule(model.id, LocalTurnKind.VISION, it)
                }
            )

            HorizontalDivider()

            // ---- Generation ----------------------------------------------
            SettingsSection("Generation")
            Text(
                "Answer length: ${outputValue.toInt()} tokens",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Caps one reply. The thermal policy may lower it further; null/auto uses the global default.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = outputValue,
                onValueChange = { outputValue = it },
                onValueChangeFinished = {
                    viewModel.setGenerationOptions(
                        model.id,
                        maxOutputTokens = outputValue.toInt(),
                        contextLimit = contextValue.toInt()
                    )
                },
                valueRange = 128f..2048f,
                steps = 14
            )
            Text(
                "Context window: ${contextValue.toInt()} tokens",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Applies the next time this model loads. The GGUF declares " +
                    (model.contextLength?.let { "$it" } ?: "no context length") +
                    "; phones are capped at 4096.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = contextValue,
                onValueChange = { contextValue = it },
                onValueChangeFinished = {
                    viewModel.setGenerationOptions(
                        model.id,
                        maxOutputTokens = outputValue.toInt(),
                        contextLimit = contextValue.toInt()
                    )
                },
                valueRange = 512f..4096f,
                steps = 6
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = {
                    outputValue = 1024f
                    contextValue = defaultContext.toFloat()
                    viewModel.setGenerationOptions(model.id, null, null)
                }) { Text("Reset to auto") }
            }

            HorizontalDivider()

            // ---- Performance ---------------------------------------------
            SettingsSection("Performance")
            SettingSwitch(
                title = "GPU acceleration (Vulkan)",
                subtitle = when {
                    !state.gpuAvailable -> "Not available in this build/device — CPU is used"
                    state.gpuThrottled -> "Paused by the thermal/battery policy"
                    state.useGpu -> "Layers run on the GPU with CPU fallback"
                    else -> "Everything runs on the CPU"
                },
                checked = state.useGpu,
                enabled = state.gpuAvailable,
                onCheckedChange = viewModel::setUseGpu
            )
            Text(
                if (state.cpuThreads == 0) "CPU threads: Auto" else "CPU threads: ${state.cpuThreads}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = threadValue,
                onValueChange = { threadValue = it },
                onValueChangeFinished = { viewModel.setCpuThreads(threadValue.toInt()) },
                valueRange = 0f..8f,
                steps = 7
            )
            Text(
                "Next answer: ${state.effectiveThreads} threads · ${state.thermalLabel} · battery ${state.batteryPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.throttleNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            // ---- Actions -------------------------------------------------
            SettingsSection("Actions")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TextButton(
                    onClick = { viewModel.loadNow(model.id) },
                    enabled = !active && !loading
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text(if (active) "Loaded" else if (loading) "Loading…" else "Load now")
                }
                TextButton(
                    onClick = viewModel::unloadNow,
                    enabled = active || loading
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                    Text("Unload")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TextButton(
                    onClick = { viewModel.benchmark(model.id) },
                    enabled = state.benchmarkingId == null
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null)
                    Text(if (state.benchmarkingId == model.id) "Benchmarking…" else "Benchmark")
                }
                TextButton(onClick = { showDelete = true }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            state.benchmarkNotice?.let { notice ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (notice.startsWith("Benchmark failed")) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::clearBenchmarkNotice) {
                            Text("OK")
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${model.displayName}?") },
            text = {
                Text(
                    "This removes the model file from your device " +
                        "(${viewModel.formatBytes(model.sizeBytes)}). " +
                        "You can download or import it again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(model.id)
                    showDelete = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.md)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.3f)
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
