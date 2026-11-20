package com.neuron.ai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.data.local.HfHubClient
import com.neuron.ai.data.local.LocalLoadState
import com.neuron.ai.data.local.LocalModelRecord
import com.neuron.ai.data.local.ModelDownloadManager
import com.neuron.ai.data.local.RecommendedModel
import com.neuron.ai.data.local.RecommendedModels
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing

/**
 * Local AI model browser. The layout mirrors the reference flow:
 *
 *   Models list  →  +  →  Hugging Face search  →  repo detail / GGUF files
 *
 * A download always goes through the file list, so the user sees the exact
 * quant file and size before anything starts. The old single-page settings
 * layout (performance + health + search all stacked together) is gone; the
 * same controls are still reachable from a model's gear button and the
 * top-right download-settings button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenModelSettings: (String) -> Unit = {}
) {
    val viewModel: LocalAiViewModel = viewModel(factory = LocalAiViewModel.Factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pane by remember { mutableStateOf(ModelsPane.LIST) }
    var detailTarget by remember { mutableStateOf<ModelDetailTarget?>(null) }
    var showDownloadSettings by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LocalModelRecord?>(null) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.import(it) } }

    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* progress still shows in-app; nothing to do on grant/deny */ }
    val requestNotificationPermission: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val openDetail: (String, String, Boolean) -> Unit = { repoId, displayName, fromSearch ->
        detailTarget = ModelDetailTarget(repoId, displayName, fromSearch)
        pane = ModelsPane.DETAIL
    }

    when (pane) {
        ModelsPane.LIST -> ModelsHome(
            state = state,
            viewModel = viewModel,
            onBack = onBack,
            onOpenDetail = openDetail,
            onOpenSettings = { onOpenModelSettings(it.id) },
            onDelete = { deleteTarget = it },
            onAdd = {
                viewModel.onSearchQueryChange("")
                pane = ModelsPane.SEARCH
            },
            onOpenDownloadSettings = { showDownloadSettings = true },
            onClearImportNotice = viewModel::clearImportNotice,
            onClearBenchmarkNotice = viewModel::clearBenchmarkNotice
        )

        ModelsPane.SEARCH -> ModelsSearch(
            state = state,
            onBack = { pane = ModelsPane.LIST },
            onQueryChange = viewModel::onSearchQueryChange,
            onSubmitSearch = viewModel::submitSearch,
            onImportLocal = { importPicker.launch(arrayOf("*/*")) },
            onOpenDetail = { repoId, displayName -> openDetail(repoId, displayName, true) }
        )

        ModelsPane.DETAIL -> {
            val target = detailTarget
            if (target == null) {
                pane = ModelsPane.LIST
            } else {
                ModelFiles(
                    state = state,
                    target = target,
                    viewModel = viewModel,
                    onBack = { pane = if (target.fromSearch) ModelsPane.SEARCH else ModelsPane.LIST },
                    onDownload = { variant ->
                        requestNotificationPermission()
                        viewModel.startDownload(target.repoId, variant, target.displayName)
                    }
                )
            }
        }
    }

    if (showDownloadSettings) {
        DownloadSettingsDialog(
            state = state,
            onDismiss = { showDownloadSettings = false },
            onWifiOnly = viewModel::setWifiOnly,
            onUseGpu = viewModel::setUseGpu,
            onCpuThreads = viewModel::setCpuThreads
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

private enum class ModelsPane { LIST, SEARCH, DETAIL }

private data class ModelDetailTarget(
    val repoId: String,
    val displayName: String,
    val fromSearch: Boolean
)

// ---------------------------------------------------------------------------
// Models list
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsHome(
    state: LocalAiUiState,
    viewModel: LocalAiViewModel,
    onBack: () -> Unit,
    onOpenDetail: (String, String, Boolean) -> Unit,
    onOpenSettings: (LocalModelRecord) -> Unit,
    onDelete: (LocalModelRecord) -> Unit,
    onAdd: () -> Unit,
    onOpenDownloadSettings: () -> Unit,
    onClearImportNotice: () -> Unit,
    onClearBenchmarkNotice: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            TopAppBar(
                title = { Text("Models", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDownloadSettings) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Download settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                state.importNotice?.let { notice ->
                    item {
                        NoticeCard(
                            text = notice,
                            isError = notice.startsWith("Import failed"),
                            onDismiss = onClearImportNotice
                        )
                    }
                }
                state.benchmarkNotice?.let { notice ->
                    item {
                        NoticeCard(
                            text = notice,
                            isError = notice.startsWith("Benchmark failed"),
                            onDismiss = onClearBenchmarkNotice
                        )
                    }
                }

                if (state.downloads.isNotEmpty()) {
                    item {
                        SectionHeader("Downloads", "Running in the background")
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

                if (state.models.isNotEmpty()) {
                    item {
                        SectionHeader("Your models", "Tap settings to manage a model")
                    }
                    items(state.models, key = { it.id }) { model ->
                        InstalledModelRow(
                            model = model,
                            loadState = state.loadState,
                            onSettings = { onOpenSettings(model) },
                            onDelete = { onDelete(model) },
                            formatBytes = viewModel::formatBytes
                        )
                    }
                }

                item {
                    SectionHeader(
                        "Available to Download",
                        "Use + button to find more models"
                    )
                }

                if (state.recommendLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "Checking availability on Hugging Face…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(state.recommended, key = { it.id }) { model ->
                    AvailableModelCard(
                        model = model,
                        fit = viewModel.fitFor(model),
                        onOpen = {
                            onOpenDetail(model.hfRepo, model.displayName, false)
                        }
                    )
                }

                item { Spacer(Modifier.height(96.dp)) }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Lifted above the usual 16dp inset so it clears the list's
                // last row and sits comfortably above the nav gesture area.
                .padding(end = Spacing.lg, bottom = 96.dp)
                .size(58.dp),
            // Strong blue with white glyph: never blends into a white page.
            containerColor = Color(0xFF2563EB),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
                pressedElevation = 4.dp
            ),
            shape = CircleShape
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Add model",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = Spacing.md, bottom = Spacing.xs)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InstalledModelRow(
    model: LocalModelRecord,
    loadState: LocalLoadState,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String
) {
    val active = loadState is LocalLoadState.Ready && loadState.modelId == model.id
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        formatBytes(model.sizeBytes),
                        model.quantization,
                        model.contextLength?.let { "$it ctx" },
                        if (active) "Active" else if (model.enabledForChat) "In chat" else "Hidden from chat"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Model settings")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete model")
            }
        }
    }
}

@Composable
private fun AvailableModelCard(
    model: RecommendedModel,
    fit: RecommendedModels.Fit,
    onOpen: () -> Unit
) {
    val quant = model.preferredQuants.firstOrNull() ?: "GGUF"
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${model.displayName} ($quant)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Size: ~${"%.1f".format(model.approxSizeGb)} GB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Skills: ${model.notes}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (fit == RecommendedModels.Fit.UNLIKELY) {
                Text(
                    "May not fit this device's RAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            model.unavailableReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (model.unavailableReason == null) {
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Download")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hugging Face search
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsSearch(
    state: LocalAiUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onImportLocal: () -> Unit,
    onOpenDetail: (String, String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val submit: () -> Unit = {
        onSubmitSearch()
        focusManager.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        TopAppBar(
            title = { Text("Search models", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = onImportLocal) {
                    Icon(Icons.Outlined.Description, contentDescription = "Import a local GGUF file")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Search lives at the TOP: tap +, type, then press the search button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("Search GGUF models") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() })
            )
            FilledTonalButton(
                onClick = submit,
                enabled = state.searchQuery.isNotBlank() && !state.searching
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Search")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)
        ) {
            if (state.searchQuery.isBlank()) {
                item {
                    Text(
                        "Search Hugging Face for a GGUF model. Tap a result to see every file and choose the exact quantization.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.lg)
                    )
                }
            }
            if (state.searching) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Searching the Hub…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            state.searchError?.let { error ->
                item {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = Spacing.sm)
                    )
                }
            }
            if (state.searchQuery.isNotBlank() && !state.searching && state.searchResults.isEmpty()) {
                item {
                    Text(
                        "No GGUF models matched \"${state.searchQuery}\". Try a model name like Gemma, Qwen or Llama.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.lg)
                    )
                }
            }
            items(state.searchResults, key = { it.repoId }) { hit ->
                SearchRepoRow(
                    hit = hit,
                    onClick = {
                        onOpenDetail(hit.repoId, hit.repoId.substringAfterLast('/'))
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        // Focus after the screen is laid out so the keyboard is ready to type.
        kotlinx.coroutines.delay(150)
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun SearchRepoRow(
    hit: SearchHit,
    onClick: () -> Unit
) {
    val author = hit.repoId.substringBefore('/')
    val repo = hit.repoId.substringAfter('/')
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm)
    ) {
        Text(
            author,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            repo,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            relativeUpdated(hit.lastModified)?.let {
                Text("Updated $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("↓ ${compactCount(hit.downloads)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("♡ ${compactCount(hit.likes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hit.gated) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Requires Token",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = Spacing.sm))
    }
}

// ---------------------------------------------------------------------------
// Repo detail / GGUF files
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFiles(
    state: LocalAiUiState,
    target: ModelDetailTarget,
    viewModel: LocalAiViewModel,
    onBack: () -> Unit,
    onDownload: (HfHubClient.SearchResult.Variant) -> Unit
) {
    val search = state.searchResults.firstOrNull { it.repoId == target.repoId }
    val recommended = state.recommended.firstOrNull { it.hfRepo == target.repoId }
    val variants = search?.variants?.takeIf { it.isNotEmpty() }
        ?: recommended?.variants.orEmpty()
    val loading = search?.loadingVariants == true
    val unavailable = search?.unavailableReason ?: recommended?.unavailableReason
    val gated = search?.gated == true
    val author = target.repoId.substringBefore('/')
    val repo = target.repoId.substringAfter('/')
    val savedFiles = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(target.repoId) {
        if (variants.isEmpty() && !loading) {
            viewModel.ensureFiles(target.repoId)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = {},
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
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            item {
                Column(Modifier.padding(bottom = Spacing.md)) {
                    Text(
                        author,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        repo,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        MetaChip("↻ ${relativeUpdated(search?.lastModified) ?: "Hugging Face"}")
                        MetaChip("↓ ${compactCount(search?.downloads ?: 0L)}")
                        MetaChip("♡ ${compactCount(search?.likes ?: 0L)}")
                    }
                    if (gated) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Requires a Hugging Face token / licence acceptance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(Spacing.lg))
                    Text(
                        "Available GGUF Files",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (loading || (variants.isEmpty() && unavailable == null)) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            "Loading files from Hugging Face…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            unavailable?.let { reason ->
                item {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = Spacing.md)
                    )
                }
            }

            items(variants, key = { it.fileName }) { variant ->
                val download = state.downloads.firstOrNull {
                    it.repoId == target.repoId && it.fileName == variant.fileName
                }
                GgufFileRow(
                    variant = variant,
                    download = download,
                    bookmarked = savedFiles[variant.fileName] == true,
                    onBookmark = {
                        savedFiles[variant.fileName] = savedFiles[variant.fileName] != true
                    },
                    onDownload = { onDownload(variant) },
                    formatBytes = viewModel::formatBytes
                )
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp)
        )
    }
}

@Composable
private fun GgufFileRow(
    variant: HfHubClient.SearchResult.Variant,
    download: ModelDownloadManager.Download?,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onDownload: () -> Unit,
    formatBytes: (Long) -> String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    variant.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatBytes(variant.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                download?.let {
                    Text(
                        downloadStatus(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (it.state) {
                            ModelDownloadManager.Download.State.FAILED ->
                                MaterialTheme.colorScheme.error
                            ModelDownloadManager.Download.State.COMPLETED ->
                                MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark file",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = "Download file")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Global download settings
// ---------------------------------------------------------------------------

@Composable
private fun DownloadSettingsDialog(
    state: LocalAiUiState,
    onDismiss: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onUseGpu: (Boolean) -> Unit,
    onCpuThreads: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download & performance") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Download over Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Model files can be several GB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.wifiOnly, onCheckedChange = onWifiOnly)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU acceleration (Vulkan)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                !state.gpuAvailable -> "Not available on this device/build — CPU is used"
                                state.gpuThrottled -> "Paused while the device is hot or the battery is low"
                                state.useGpu -> "Layers run on the GPU; CPU fallback if it fails"
                                else -> "Disabled — everything runs on the CPU"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.useGpu,
                        enabled = state.gpuAvailable,
                        onCheckedChange = onUseGpu
                    )
                }
                Text(
                    if (state.cpuThreads == 0) "CPU threads: Auto"
                    else "CPU threads: ${state.cpuThreads}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = state.cpuThreads.toFloat(),
                    onValueChange = { onCpuThreads(it.toInt()) },
                    valueRange = 0f..8f,
                    steps = 7
                )
                HorizontalDivider()
                Text(
                    "Thermal: ${state.thermalLabel} · Battery ${state.batteryPercent}%" +
                        (if (state.charging) " (charging)" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    state.throttleNotice ?: "No throttling — full speed on the next answer",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.throttleNotice != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

// ---------------------------------------------------------------------------
// Shared dialogs / rows
// ---------------------------------------------------------------------------

@Composable
private fun DownloadRow(
    download: ModelDownloadManager.Download,
    formatBytes: (Long) -> String,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    val fraction = (download.downloadedBytes.toFloat() /
        download.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(download.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                downloadStatus(download),
                style = MaterialTheme.typography.labelSmall,
                color = when (download.state) {
                    ModelDownloadManager.Download.State.FAILED -> MaterialTheme.colorScheme.error
                    ModelDownloadManager.Download.State.COMPLETED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (download.state == ModelDownloadManager.Download.State.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row {
                when (download.state) {
                    ModelDownloadManager.Download.State.DOWNLOADING ->
                        TextButton(onClick = { onPause(download.downloadId) }) { Text("Pause") }
                    ModelDownloadManager.Download.State.PAUSED,
                    ModelDownloadManager.Download.State.WAITING_FOR_WIFI ->
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

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.2fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun relativeUpdated(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val then = java.time.Instant.parse(iso)
        val days = java.time.Duration.between(then, java.time.Instant.now()).toDays()
        when {
            days <= 0 -> "today"
            days == 1L -> "1 day ago"
            days < 7 -> "$days days ago"
            days < 30 -> "${days / 7} week(s) ago"
            days < 365 -> "${days / 30} month(s) ago"
            else -> "${days / 365} year(s) ago"
        }
    }.getOrNull()
}

private fun downloadStatus(download: ModelDownloadManager.Download): String {
    val fraction = (download.downloadedBytes.toFloat() /
        download.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
    return when (download.state) {
        ModelDownloadManager.Download.State.DOWNLOADING ->
            "Downloading… ${(fraction * 100).toInt()}%"
        ModelDownloadManager.Download.State.PAUSED -> "Paused"
        ModelDownloadManager.Download.State.WAITING_FOR_WIFI ->
            download.error ?: "Waiting for Wi-Fi…"
        ModelDownloadManager.Download.State.COMPLETED -> "Installed"
        ModelDownloadManager.Download.State.FAILED -> download.error ?: "Failed"
    }
}
