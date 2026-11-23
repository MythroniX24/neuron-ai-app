package com.neuron.ai.ui.providers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.data.provider.ProviderPresets
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.theme.Spacing

/**
 * Add/edit provider. New providers start from a preset (OpenAI, Gemini, Groq,
 * xAI…); models can be loaded straight from the endpoint and picked as chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    container: AppContainer,
    providerId: String?,
    presetId: String? = null,
    onBack: () -> Unit
) {
    val viewModel: ProvidersViewModel =
        viewModel(factory = ProvidersViewModelFactory(container))
    val form by viewModel.form.collectAsStateWithLifecycle()

    LaunchedEffect(providerId, presetId) {
        if (providerId != null) {
            viewModel.loadForEdit(providerId)
        } else if (presetId != null) {
            viewModel.applyPreset(presetId)
        } else {
            viewModel.loadForEdit(null)
        }
    }
    LaunchedEffect(form.saved) { if (form.saved) onBack() }

    // Discovered-model list starts collapsed; the header row toggles it.
    var modelsExpanded by remember { mutableStateOf(false) }
    var modelSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(form.discoveredModels.size) {
        if (form.discoveredModels.isNotEmpty()) {
            modelsExpanded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    if (providerId == null) "Add Provider" else "Edit Provider",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // ---- Preset picker (new providers only) ------------------------------
            if (providerId == null) {
                Text(
                    text = "Choose a provider",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    listOf("openai", "gemini", "groq").forEach { id ->
                        PresetChip(
                            label = ProviderPresets.byId(id)!!.displayName,
                            selected = form.presetId == id,
                            onClick = { viewModel.applyPreset(id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    listOf("xai", "openrouter", "ollama").forEach { id ->
                        PresetChip(
                            label = ProviderPresets.byId(id)!!.displayName
                                .substringBefore(" ("),
                            selected = form.presetId == id,
                            onClick = { viewModel.applyPreset(id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { value -> viewModel.updateForm { it.copy(name = value) } },
                label = { Text("Provider name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { value -> viewModel.updateForm { it.copy(baseUrl = value) } },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { value -> viewModel.updateForm { it.copy(apiKey = value) } },
                label = {
                    Text(if (providerId == null) "API key" else "API key (unchanged if blank)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- Discover & Select Models ----------------------------------------
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = viewModel::loadModels,
                            enabled = !form.isLoadingModels && form.baseUrl.isNotBlank(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (form.isLoadingModels) "Fetching Models…" else "Fetch Models")
                        }
                        if (form.isLoadingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (form.discoveredModels.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.clickable { modelsExpanded = !modelsExpanded }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "${form.discoveredModels.size} found" +
                                            if (form.selectedModels.isNotEmpty()) " • ${form.selectedModels.size} picked" else "",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (modelsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    form.modelsError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Spacing.sm)
                        )
                    }

                    if (modelsExpanded && form.discoveredModels.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.sm))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            thickness = 0.8.dp
                        )
                        Spacer(Modifier.height(Spacing.sm))

                        // Search Models Input
                        OutlinedTextField(
                            value = modelSearchQuery,
                            onValueChange = { modelSearchQuery = it },
                            placeholder = {
                                Text(
                                    "Search models (e.g. llama, gpt, deepseek)…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (modelSearchQuery.isNotBlank()) {
                                    IconButton(
                                        onClick = { modelSearchQuery = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Clear search",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        val filteredModels = remember(form.discoveredModels, modelSearchQuery) {
                            if (modelSearchQuery.isBlank()) form.discoveredModels
                            else form.discoveredModels.filter { it.contains(modelSearchQuery, ignoreCase = true) }
                        }

                        // Quick Selection Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xs)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                TextButton(
                                    onClick = {
                                        viewModel.updateForm { it.copy(selectedModels = it.selectedModels + filteredModels) }
                                    },
                                    enabled = filteredModels.isNotEmpty()
                                ) {
                                    Text(
                                        text = if (modelSearchQuery.isBlank()) "Select All" else "Select Matches (${filteredModels.size})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                if (form.selectedModels.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            viewModel.updateForm { it.copy(selectedModels = emptySet()) }
                                        }
                                    ) {
                                        Text(
                                            "Clear (${form.selectedModels.size})",
                                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${filteredModels.size} of ${form.discoveredModels.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (filteredModels.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.md),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No models match \"$modelSearchQuery\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                filteredModels.forEach { modelId ->
                                    val isSelected = modelId in form.selectedModels
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                        border = BorderStroke(
                                            0.8.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleModel(modelId) }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { viewModel.toggleModel(modelId) },
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = modelId,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                                ),
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                       else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (form.selectedModels.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = "✓ ${form.selectedModels.size} models selected to be enabled for this provider",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.xs)
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = form.modelIdsText,
                onValueChange = { value ->
                    viewModel.updateForm { it.copy(modelIdsText = value) }
                },
                label = { Text("Model IDs (manual)") },
                placeholder = { Text("gpt-4o-mini, llama-3.1-70b (comma separated)") },
                supportingText = {
                    Text("Optional if you selected models above.")
                },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = form.headersText,
                onValueChange = { value ->
                    viewModel.updateForm { it.copy(headersText = value) }
                },
                label = { Text("Custom headers") },
                placeholder = { Text("X-Custom-Header: value (one per line)") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            ToggleRow(
                title = "Vision",
                subtitle = "Send image attachments to this provider",
                checked = form.visionEnabled,
                onChecked = { checked ->
                    viewModel.updateForm { it.copy(visionEnabled = checked) }
                }
            )

            ToggleRow(
                title = "Tool calling",
                subtitle = "Let the agent use built-in tools",
                checked = form.toolsEnabled,
                onChecked = { checked ->
                    viewModel.updateForm { it.copy(toolsEnabled = checked) }
                }
            )

            form.testResult?.let { result ->
                Text(
                    text = when (result) {
                        ProviderFormState.TestResult.SUCCESS ->
                            "✓ Connection successful"
                        ProviderFormState.TestResult.FAILURE ->
                            "✕ " + (form.testMessage ?: "Connection failed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result == ProviderFormState.TestResult.SUCCESS) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !form.isTesting && form.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (form.isTesting) "Testing…" else "Test connection")
                }
                androidx.compose.material3.Button(
                    onClick = { viewModel.save(providerId) },
                    enabled = !form.isSaving &&
                        form.name.isNotBlank() &&
                        form.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (form.isSaving) "Saving…" else "Save")
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) {
            { Text("✓", style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
        modifier = modifier
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
