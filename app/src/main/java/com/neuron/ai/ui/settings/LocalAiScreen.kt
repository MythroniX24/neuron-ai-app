package com.neuron.ai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.RecommendedModel
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing

/**
 * Settings → Local AI: downloaded/imported on-device models, import from
 * file, curated recommendations, ticks that feed the chat model switcher,
 * per-model detail (benchmark/delete), and the storage summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: LocalAiViewModel = viewModel(factory = LocalAiViewModel.Factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.import(it) } }

    var deleteTarget by remember { mutableStateOf<LocalModelRecord?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Local AI", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // ---- Notices -------------------------------------------------
            state.importNotice?.let { notice ->
                item {
                    NoticeCard(
                        text = notice,
                        isError = notice.startsWith("Import failed"),
                        onDismiss = { viewModel.clearImportNotice() }
                    )
                }
            }
            state.benchmarkNotice?.let { notice ->
                item {
                    NoticeCard(
                        text = notice,
                        isError = notice.startsWith("Benchmark failed"),
                        onDismiss = { viewModel.clearBenchmarkNotice() }
                    )
                }
            }

            // ---- Storage summary ------------------------------------------
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Column {
                            Text(
                                "Local models use ${viewModel.formatBytes(state.storageUsedBytes)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Device RAM: ${"%.1f".format(state.totalRamBytes / 1e9)} GB" +
                                    " · free ${"%.1f".format(state.freeRamBytes / 1e9)} GB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- Actions ---------------------------------------------------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { importPicker.launch(arrayOf("*/*")) },
                        enabled = !state.importing
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Spacer(Modifier.size(Spacing.xs))
                        Text(if (state.importing) "Validating…" else "Import from file")
                    }
                }
            }

            // ---- Downloaded / imported models -------------------------------
            if (state.models.isNotEmpty()) {
                item {
                    Text(
                        "Your models",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(state.models, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        loadState = state.loadState,
                        benchmarking = state.benchmarkingId == model.id,
                        enabled = model.enabledForChat,
                        onToggleEnabled = { viewModel.setEnabled(model.id, it) },
                        onLoad = { viewModel.loadNow(model.id) },
                        onBenchmark = { viewModel.benchmark(model.id) },
                        onDelete = { deleteTarget = model },
                        formatBytes = viewModel::formatBytes
                    )
                }
            }

            // ---- Recommended (curated, shown before any search) --------------
            item {
                Text(
                    "Recommended",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(viewModel.recommended, key = { it.id }) { model ->
                RecommendedCard(
                    model = model,
                    fit = viewModel.fitFor(model),
                    onDownload = {
                        // Milestone 4: download manager. Until then, guide the
                        // user to grab the file and import it.
                    }
                )
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }

    deleteTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("This removes the model file from your device (${viewModel.formatBytes(model.sizeBytes)}). You can download or import it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(model.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val color by animateColorAsState(
        if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        label = "notice"
    )
    Surface(shape = RoundedCornerShape(12.dp), color = color) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModelRecord,
    loadState: LocalLoadState,
    benchmarking: Boolean,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onLoad: () -> Unit,
    onBenchmark: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String
) {
    val isActive = loadState is LocalLoadState.Ready && loadState.modelId == model.id
    val isLoading = loadState is LocalLoadState.Loading && loadState.modelId == model.id
    val failed = loadState as? LocalLoadState.Failed

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(
                            formatBytes(model.sizeBytes),
                            model.quantization ?: "unknown quant",
                            model.contextLength?.let { "$it ctx" } ?: "ctx n/a",
                            when {
                                isActive -> "Active"
                                isLoading -> "Loading…"
                                failed?.modelId == model.id -> "Load error"
                                else -> "Available"
                            }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            failed?.modelId == model.id -> MaterialTheme.colorScheme.error
                            isActive -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Chat",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Checkbox(checked = enabled, onCheckedChange = onToggleEnabled)
                }
            }

            failed?.takeIf { it.modelId == model.id }?.let {
                Text(
                    it.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onLoad, enabled = !isActive && !isLoading) {
                    Text(if (isActive) "Loaded" else "Load")
                }
                TextButton(onClick = onBenchmark, enabled = !benchmarking) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (benchmarking) "Running…" else "Benchmark")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun RecommendedCard(
    model: RecommendedModel,
    fit: RecommendedModels.Fit,
    onDownload: () -> Unit
) {
    val dimmed = fit == RecommendedModels.Fit.UNLIKELY
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.alpha(if (dimmed) 0.55f else 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "~${model.approxSizeGb} GB · min RAM ${model.minRamGb.toInt()} GB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when (fit) {
                        RecommendedModels.Fit.FINE -> model.notes
                        RecommendedModels.Fit.TIGHT -> model.notes + " — may be tight on free RAM"
                        RecommendedModels.Fit.UNLIKELY ->
                            "May be too large for your device (needs ${model.minRamGb.toInt()} GB RAM)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fit == RecommendedModels.Fit.UNLIKELY) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = onDownload, enabled = false) {
                Text("Get")
            }
        }
    }
}
