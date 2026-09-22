package com.neuron.ai.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.settings.ThemeMode
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
    onOpenTasks: () -> Unit = {}
) {
    val viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModelFactory(container))
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Column(modifier = Modifier.padding(start = Spacing.md)) {
                    Text(
                        text = "Neuron-AI",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Version 0.2.0 — Phase 1",
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
