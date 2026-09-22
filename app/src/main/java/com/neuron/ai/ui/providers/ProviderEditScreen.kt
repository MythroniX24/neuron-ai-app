package com.neuron.ai.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.PrimaryButton
import com.neuron.ai.ui.theme.Spacing

/**
 * Add/edit provider form. The API key field is write-only: existing keys are
 * never echoed back. "Test connection" performs a live /models call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    container: AppContainer,
    providerId: String?,
    onBack: () -> Unit
) {
    val viewModel: ProvidersViewModel =
        viewModel(factory = ProvidersViewModelFactory(container))
    val form by viewModel.form.collectAsStateWithLifecycle()

    LaunchedEffect(providerId) { viewModel.loadForEdit(providerId) }
    LaunchedEffect(form.saved) { if (form.saved) onBack() }

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
            OutlinedTextField(
                value = form.name,
                onValueChange = { value -> viewModel.updateForm { it.copy(name = value) } },
                label = { Text("Provider name") },
                placeholder = { Text("e.g. OpenAI, Groq, OpenRouter") },
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

            OutlinedTextField(
                value = form.modelIdsText,
                onValueChange = { value ->
                    viewModel.updateForm { it.copy(modelIdsText = value) }
                },
                label = { Text("Model IDs") },
                placeholder = { Text("gpt-4o-mini, llama-3.1-70b (comma separated)") },
                supportingText = {
                    Text("Leave empty to discover models from the provider.")
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
                            "✕ Connection failed — check URL, key and network"
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
                androidx.compose.material3.OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !form.isTesting && form.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (form.isTesting) "Testing…" else "Test connection")
                }
                PrimaryButton(
                    text = if (form.isSaving) "Saving…" else "Save",
                    onClick = { viewModel.save(providerId) },
                    enabled = !form.isSaving &&
                        form.name.isNotBlank() &&
                        form.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
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
