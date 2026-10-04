package com.neuron.ai.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Debug console.
 *
 * Written because "Model file could not be loaded (corrupt or unsupported
 * GGUF)" told the user nothing: llama.cpp knows exactly why a load failed
 * (out of memory, unknown architecture, truncated file, a Vulkan device that
 * cannot allocate the buffer) and says so on its own logger, which we now
 * capture. This screen surfaces that log next to the device memory, the
 * build flags and the actual file on disk — and lets the whole thing be
 * copied into a bug report without logcat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: DebugConsoleViewModel =
        viewModel(factory = DebugConsoleViewModelFactory(container))
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // Reading the native log and stat-ing model files touches disk, so it
    // must not run on the main thread.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Debug console", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        if (snapshot == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Collecting…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        val data = snapshot ?: return@Column

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch { viewModel.refresh(); busy = false }
                    },
                    enabled = !busy
                ) { Text("Refresh") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { viewModel.clearLog() }
                            viewModel.refresh()
                        }
                    },
                    enabled = !busy
                ) { Text("Clear log") }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(viewModel.reportText(data))) },
                    colors = ButtonDefaults.outlinedButtonColors()
                ) { Text("Copy report") }
            }

            SectionTitle("Engine")
            KeyValue("Native library", if (data.nativeLibraryLoaded) "loaded" else "NOT LOADED")
            KeyValue("Build", data.engineVersion)
            KeyValue("Backends loaded", data.backends)
            if (data.backends.startsWith("NONE")) {
                Text(
                    text = "ggml found no compute backend — every model will fail with " +
                        "\"no backends are loaded\". This is an installation problem, not a " +
                        "bad model file. Reinstalling the APK normally fixes it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            KeyValue("GPU (Vulkan)", yesNo(data.gpuAvailable))
            KeyValue("Vision projector loaded", yesNo(data.visionAvailable))
            KeyValue("Registered models", data.registeredModels.toString())
            if (data.registeredModels == 0) {
                Text(
                    text = "No model is registered — nothing can be loaded until a download finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            SectionTitle("Device")
            KeyValue("Total RAM", data.totalRamGb)
            KeyValue("Free RAM", data.freeRamGb)
            KeyValue("Models on disk", data.modelsOnDisk)
            KeyValue("Storage used", data.storageUsed)

            SectionTitle("Model files")
            if (data.modelFiles.isEmpty()) {
                Text(
                    text = "No .gguf files in the models directory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                data.modelFiles.forEach { file ->
                    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = file.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (file.suspect) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionTitle("llama.cpp log")
            Text(
                text = if (data.logText.isBlank()) "(empty — nothing logged yet)" else data.logText.trimEnd(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = Spacing.lg)
            )
        }
    }
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

@Composable
private fun SectionTitle(text: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}