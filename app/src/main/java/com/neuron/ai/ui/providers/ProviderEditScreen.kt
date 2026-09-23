package com.neuron.ai.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.ui.unit.dp
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

            // ---- Load models (collapsible; collapsed by default so the
            //      long model list never floods the form) --------------------------
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = viewModel::loadModels,
                            enabled = !form.isLoadingModels && form.baseUrl.isNotBlank()
                        ) {
                            Text(if (form.isLoadingModels) "Loading…" else "Load models")
                        }
                        if (form.isLoadingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        // Expand/collapse toggle — only when there is a list to show.
                        if (form.discoveredModels.isNotEmpty()) {
                            Text(
                                text = "${form.discoveredModels.size} found" +
                                    if (form.selectedModels.isNotEmpty()) {
                                        " · ${form.selectedModels.size} selected"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { modelsExpanded = !modelsExpanded }) {
                                Icon(
                                    imageVector = if (modelsExpanded) {
                                        Icons.Outlined.KeyboardArrowUp
                                    } else {
                                        Icons.Outlined.KeyboardArrowDown
                                    },
                                    contentDescription = if (modelsExpanded) {
                                        "Collapse model list"
                                    } else {
                                        "Expand model list"
                                    }
                                )
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
                        // Plain Column: this card sits inside a verticalScroll
                        // parent, where a LazyColumn would crash on infinite height.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            form.discoveredModels.forEach { modelId ->
                                ListItem(
                                    headlineContent = { Text(modelId) },
                                    leadingContent = {
                                        Checkbox(
                                            checked = modelId in form.selectedModels,
                                            onCheckedChange = { viewModel.toggleModel(modelId) }
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.toggleModel(modelId)
                                    }
                                )
                            }
                        }
                        if (form.selectedModels.isNotEmpty()) {
                            Text(
                                text = "${form.selectedModels.size} selected — they will be saved as this provider's models",
                                style = MaterialTheme.typography.labelSmall,
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
