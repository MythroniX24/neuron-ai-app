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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Switch
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
import androidx.compose.material3.Slider
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
import com.neuron.ai.data.local.HfHubClient
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.LocalTurnKind
import com.neuron.ai.data.local.ModelRoutingRule
import com.neuron.ai.data.local.RecommendedModel
import com.neuron.ai.data.local.RecommendedModels
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
    var variantTarget by remember {
        mutableStateOf<VariantTarget?>(null)
    }

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
                        visionReady = viewModel.visionReady(model),
                        routing = viewModel.routingRuleFor(model.id),
                        onRouting = { kind, enabled ->
                            viewModel.setRoutingRule(model.id, kind, enabled)
                        },
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

            // ---- Hub search (ALWAYS visible) -------------------------------
            // The field used to appear only AFTER a query was typed, and the
            // recommended tap filled it with a display name ("Gemma 2 2B")
            // that the Hub returns nothing for — leaving the screen empty
            // with no way back. Search is now a plain, always-on field.
            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search Hugging Face (GGUF models)…") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    }
                )
            }
            state.searchError?.let { error ->
                item {
                    NoticeCard(
                        text = error,
                        isError = true,
                        onDismiss = { viewModel.clearSearchError() }
                    )
                }
            }
            if (state.searching) {
                item {
                    Text(
                        "Searching the Hub…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.searchQuery.isNotBlank() && !state.searching &&
                state.searchResults.isEmpty()
            ) {
                item {
                    Text(
                        "No GGUF models matched \"${state.searchQuery}\". Try a model " +
                            "name like Qwen, Llama or Gemma.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.searchResults, key = { it.repoId }) { hit ->
                SearchResultCard(
                    hit = hit,
                    onPickVariant = { repoId, variants, displayName ->
                        variantTarget = VariantTarget(repoId, variants, displayName)
                    }
                )
            }
            if (state.searchQuery.isNotBlank()) {
                item {
                    TextButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Text("Clear search")
                    }
                }
            }

            // ---- Downloads in progress (any source) -----------------------
            if (state.downloads.isNotEmpty()) {
                item {
                    Text(
                        "Downloads",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(state.downloads, key = { it.downloadId }) { download ->
                    DownloadRow(
                        download = download,
                        formatBytes = viewModel::formatBytes,
                        onPause = viewModel::pauseDownload,
                        onResume = viewModel::resumeDownload,
                        onCancel = viewModel::cancelDownload
                    )
                }
            }

            // ---- Recommended (curated) -------------------------------------
            // Shown while the query is blank OR produced nothing, so the
            // catalog can never be the thing that "disappears".
            if (state.searchQuery.isBlank() || state.searchResults.isEmpty()) {
                item {
                    Text(
                        "Recommended",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (state.recommendLoading) {
                    item {
                        Text(
                            "Checking availability on Hugging Face…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(state.recommended, key = { it.id }) { model ->
                    RecommendedCard(
                        model = model,
                        fit = viewModel.fitFor(model),
                        onDownload = { viewModel.quickDownloadRecommended(model) },
                        onShowFiles = {
                            variantTarget = VariantTarget(
                                model.hfRepo,
                                viewModel.recommendedVariants(model),
                                model.displayName
                            )
                        }
                    )
                }
            }

            // ---- Wi-Fi-only toggle -----------------------------------------
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Download over Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Model files can be several GB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) })
                }
            }

            // ---- Performance (milestone 5) --------------------------------
            item {
                Column {
                    Text(
                        "Performance",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text("GPU acceleration (Vulkan)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    !state.gpuAvailable -> "Not available on this device/build — CPU is used"
                                    state.gpuThrottled -> "Paused while the device is hot or the battery is low"
                                    state.useGpu -> "Layers run on the GPU; falls back to CPU if it fails"
                                    else -> "Disabled — everything runs on the CPU"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.useGpu,
                            enabled = state.gpuAvailable,
                            onCheckedChange = { viewModel.setUseGpu(it) }
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.cpuThreads == 0) "CPU threads: Auto"
                                else "CPU threads: ${state.cpuThreads}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Auto picks the device default (2-6)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = state.cpuThreads.toFloat(),
                                onValueChange = { viewModel.setCpuThreads(it.toInt()) },
                                valueRange = 0f..8f,
                                steps = 7
                            )
                        }
                    }
                }
            }

            // ---- Device health / thermal throttle (milestone 7) ------
            // Live thermal + battery state and the throttle the policy WILL
            // apply on the next on-device generation.
            item {
                Column {
                    Text(
                        "Device status",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(Spacing.md)
                    ) {
                        Text(
                            "Thermal: ${state.thermalLabel} · Battery ${state.batteryPercent}%" +
                                (if (state.charging) " (charging)" else ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.powerSaveMode) {
                            Text(
                                "Battery saver is on",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            state.throttleNotice
                                ?: "No throttling — full speed on the next answer",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.throttleNotice != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (state.effectiveThreads > 0) {
                            Text(
                                "Next answer: ${state.effectiveThreads} thread(s)" +
                                    if (state.gpuThrottled) ", GPU off" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Milestone 8: how much of the last prompt came out of
                        // the KV cache instead of being recomputed.
                        if (state.promptCacheReusePercent > 0) {
                            Text(
                                "Last prompt: ${state.promptCacheReusePercent}% reused from " +
                                    "cache (no recompute)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }


    // Quantization picker when a search hit is expanded for download.
    variantTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { variantTarget = null },
            title = { Text("Pick a quantization") },
            text = {
                Column {
                    Text(
                        "Lower-bit = faster, smaller, slightly lower quality.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    target.variants.forEach { variant ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.startDownload(
                                        target.repoId, variant, target.displayName
                                    )
                                    variantTarget = null
                                }
                                .padding(vertical = Spacing.sm)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(variant.fileName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    viewModel.formatBytes(variant.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { variantTarget = null }) { Text("Cancel") }
            }
        )
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

/** Quantization picker target: one repo + its GGUF variants. */
private data class VariantTarget(
    val repoId: String,
    val variants: List<com.neuron.ai.data.local.HfHubClient.SearchResult.Variant>,
    val displayName: String
)

@Composable
private fun SearchResultCard(
    hit: com.neuron.ai.ui.settings.SearchHit,
    onPickVariant: (String, List<com.neuron.ai.data.local.HfHubClient.SearchResult.Variant>, String) -> Unit
) {
    val displayName = hit.repoId.substringAfterLast('/')
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(
                    "${hit.downloads} downloads",
                    hit.license?.let { it },
                    if (hit.loadingVariants) "loading variants…" else "${hit.variants.size} GGUF files"
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Why there is nothing to download (gated repo, renamed, rate
            // limited) — shown instead of a silently dead card.
            hit.unavailableReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (hit.variants.isNotEmpty()) {
                TextButton(onClick = { onPickVariant(hit.repoId, hit.variants, displayName) }) {
                    Text("Get")
                }
            }
        }
    }
}

/**
 * One tracked download with progress + pause/resume/cancel. Lives in its own
 * section (not inside the card that started it) so a download launched from
 * the recommended list is still visible.
 */
@Composable
private fun DownloadRow(
    download: com.neuron.ai.data.local.ModelDownloadManager.Download,
    formatBytes: (Long) -> String,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    val state = download.state
    val fraction = (download.downloadedBytes.toFloat() /
        download.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(download.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val status = when (state) {
                com.neuron.ai.data.local.ModelDownloadManager.Download.State.DOWNLOADING ->
                    "Downloading… ${(fraction * 100).toInt()}%"
                com.neuron.ai.data.local.ModelDownloadManager.Download.State.PAUSED -> "Paused"
                com.neuron.ai.data.local.ModelDownloadManager.Download.State.WAITING_FOR_WIFI ->
                    download.error ?: "Waiting for Wi-Fi…"
                com.neuron.ai.data.local.ModelDownloadManager.Download.State.COMPLETED ->
                    "Installed — pick it under \"Your models\""
                com.neuron.ai.data.local.ModelDownloadManager.Download.State.FAILED ->
                    download.error ?: "Failed"
            }
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = when (state) {
                    com.neuron.ai.data.local.ModelDownloadManager.Download.State.FAILED ->
                        MaterialTheme.colorScheme.error
                    com.neuron.ai.data.local.ModelDownloadManager.Download.State.COMPLETED ->
                        MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (state == com.neuron.ai.data.local.ModelDownloadManager.Download.State.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row {
                when (state) {
                    com.neuron.ai.data.local.ModelDownloadManager.Download.State.DOWNLOADING ->
                        TextButton(onClick = { onPause(download.downloadId) }) { Text("Pause") }
                    com.neuron.ai.data.local.ModelDownloadManager.Download.State.PAUSED,
                    com.neuron.ai.data.local.ModelDownloadManager.Download.State.WAITING_FOR_WIFI ->
                        TextButton(onClick = { onResume(download.downloadId) }) { Text("Resume") }
                    else -> {}
                }
                TextButton(onClick = { onCancel(download.downloadId) }) { Text("Cancel") }
            }
        }
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
    visionReady: Boolean,
    routing: ModelRoutingRule,
    onRouting: (LocalTurnKind, Boolean) -> Unit,
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

            // Milestone 9: vision is real only with a projector file imported
            // beside the GGUF — say which state this model is actually in.
            if (com.neuron.ai.data.local.LocalModelRouter.architectureSupportsVision(
                    model.architecture
                )
            ) {
                Text(
                    if (visionReady) {
                        "Vision ready — projector detected"
                    } else {
                        "Vision model, but no mmproj projector imported yet (images will be refused)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (visionReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
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

            // Milestone 9: per-model routing rules — which kind of turn this
            // model should serve. The first enabled rule wins, and a vision
            // rule is only offered once a projector is really there.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                RoutingToggle("Text", routing.text) {
                    onRouting(LocalTurnKind.TEXT, !routing.text)
                }
                RoutingToggle("Tools", routing.tools) {
                    onRouting(LocalTurnKind.TOOLS, !routing.tools)
                }
                RoutingToggle(
                    label = "Vision",
                    active = routing.vision,
                    enabled = visionReady
                ) { onRouting(LocalTurnKind.VISION, !routing.vision) }
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

/** One "use for <kind>" toggle on a model card. */
@Composable
private fun RoutingToggle(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            if (active) "✓ $label" else label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                active -> MaterialTheme.colorScheme.primary
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun RecommendedCard(
    model: RecommendedModel,
    fit: RecommendedModels.Fit,
    onDownload: () -> Unit,
    onShowFiles: () -> Unit
) {
    val dimmed = fit == RecommendedModels.Fit.UNLIKELY
    val blocked = model.unavailableReason != null
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
                // Live Hub verdict for this repo (gated / renamed / rate
                // limited) — replaces a download button that could only fail.
                model.unavailableReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (model.downloadable) {
                    Text(
                        "Recommended file: " +
                            (HfHubClient.selectVariant(model.variants, model.preferredQuants)
                                ?.fileName ?: model.variants.first().fileName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onDownload, enabled = !blocked) {
                    Text(if (blocked) "Unavailable" else "Get")
                }
                if (model.downloadable) {
                    TextButton(onClick = onShowFiles) { Text("All files") }
                }
            }
        }
    }
}
