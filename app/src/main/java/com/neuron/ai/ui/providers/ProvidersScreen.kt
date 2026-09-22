package com.neuron.ai.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.provider.ProviderConfig
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.EmptyState
import com.neuron.ai.ui.theme.Spacing

/**
 * Provider manager: list of OpenAI-compatible endpoints with enable toggle,
 * edit and delete. Credentials themselves are never shown here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEditProvider: (String) -> Unit,
    onAddProvider: () -> Unit
) {
    val viewModel: ProvidersViewModel =
        viewModel(factory = ProvidersViewModelFactory(container))
    val configs by viewModel.configs.collectAsStateWithLifecycle()

    var deleting by remember { mutableStateOf<ProviderConfig?>(null) }

    Scaffold(
        modifier = Modifier.systemBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProvider) {
                Icon(Icons.Outlined.Add, contentDescription = "Add provider")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TopAppBar(
                title = { Text("AI Providers", style = MaterialTheme.typography.titleMedium) },
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

            if (configs.isEmpty()) {
                EmptyState(
                    title = "No providers",
                    description = "Add an OpenAI-compatible provider to start chatting.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xl)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = Spacing.lg, vertical = Spacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(configs, key = { it.id }) { config ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    config.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        config.baseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val models = config.modelIds.size
                                    Text(
                                        if (models == 0) {
                                            "Models discovered on connect"
                                        } else {
                                            "$models model${if (models == 1) "" else "s"}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingContent = {
                                Text(
                                    text = config.displayName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onEditProvider(config.id) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { deleting = config }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = config.enabled,
                                        onCheckedChange = { viewModel.setEnabled(config.id, it) }
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.background
                            ),
                            modifier = Modifier.clickable { onEditProvider(config.id) }
                        )
                    }
                }
            }
        }
    }

    deleting?.let { config ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete provider?") },
            text = {
                Text(
                    "“${config.displayName}” will be removed and its stored API key " +
                        "erased from secure storage.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(config.id)
                    deleting = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}
