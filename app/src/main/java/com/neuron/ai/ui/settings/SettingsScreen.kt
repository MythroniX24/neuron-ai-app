package com.neuron.ai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.settings.ThemeMode
import com.neuron.ai.data.storage.DeviceStorage
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.BrandMark
import com.neuron.ai.ui.theme.Spacing

private val THEME_OPTIONS = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit = {},
    onOpenConversations: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenLocalAi: () -> Unit = {},
    onOpenDebugConsole: () -> Unit = {},
    onOpenConnectedApps: () -> Unit = {}
) {
    val viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModelFactory(container))
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val memoryEntries by viewModel.memoryEntries.collectAsStateWithLifecycle()
    val memoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
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
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg)
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            THEME_OPTIONS.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setThemeMode(option) }
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = themeMode == option,
                        onClick = { viewModel.setThemeMode(option) }
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            Text(
                text = "Data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            SettingsLink("Conversations", "Search, rename and delete chats") {
                onOpenConversations()
            }
            SettingsLink("Tasks", "See and stop running agent tasks") {
                onOpenTasks()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            // ---- Storage access: the agent works on real device files -------
            Text(
                text = "Storage access",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val context = LocalContext.current
            var storageGranted by remember { mutableStateOf(DeviceStorage.hasAccess(context)) }
            // Coming back from the system screen must refresh the state — the
            // permission lives outside this process.
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, context) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        storageGranted = DeviceStorage.hasAccess(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            SettingsLink(
                title = if (storageGranted) {
                    "All files access — granted"
                } else {
                    "All files access — tap to grant"
                },
                subtitle = if (storageGranted) {
                    "The AI can read and edit any file on this phone: Downloads, " +
                        "Documents, DCIM, project folders."
                } else {
                    "Without it the file tools can only see the app's own folder. " +
                        "Grant it so the AI can read and edit your files."
                }
            ) {
                val target = DeviceStorage.accessIntent(context)
                    ?: android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + context.packageName)
                    )
                runCatching { context.startActivity(target) }
                storageGranted = DeviceStorage.hasAccess(context)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            // ---- Memory (Milestone 3): inspect, disable, delete ----------------
            Text(
                text = "Memory",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Remember saved facts", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Nothing is saved automatically. Secrets are never stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = memoryEnabled,
                    onCheckedChange = { viewModel.setMemoryEnabled(it) }
                )
            }
            if (memoryEntries.isNotEmpty()) {
                memoryEntries.take(20).forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xs)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.key,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                entry.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            viewModel.deleteMemory(entry.id)
                        }) { Text("Delete") }
                    }
                }
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.clearAllMemory()
                }) { Text("Clear all memory", color = MaterialTheme.colorScheme.error) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            // ---- Connected Apps: Google Drive/Gmail/Sheets/Docs ---------------
            Text(
                text = "Connected Apps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SettingsLink(
                "Google account",
                "Drive, Gmail, Sheets and Docs tools — Google calls run on the connector " +
                    "backend, never on this phone"
            ) {
                onOpenConnectedApps()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            Text(
                text = "AI Providers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SettingsLink(
                "Manage providers",
                "OpenAI-compatible endpoints, keys stored in the Android Keystore"
            ) {
                onOpenProviders()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            Text(
                text = "Local AI",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SettingsLink(
                "On-device models",
                "Run AI models fully on this phone — no API key, no internet at inference time"
            ) {
                onOpenLocalAi()
            }
            SettingsLink(
                "Debug console",
                "Engine build, device memory, model files and llama.cpp's own log — " +
                    "the reason a local model refused to load"
            ) {
                onOpenDebugConsole()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.lg))

            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Column(modifier = Modifier.padding(start = Spacing.md)) {
                    Text(
                        text = "Neuron-AI",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Version 1.2.2",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
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
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
