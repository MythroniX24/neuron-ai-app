package com.neuron.ai.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.settings.ThemeMode
import com.neuron.ai.data.storage.DeviceStorage
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.BrandMark

/**
 * Executive-grade, minimal Settings screen for Neuron-AI.
 * Inspired by clean, production-grade AI interfaces (ChatGPT / Claude mobile).
 */
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
    onOpenConnectedApps: () -> Unit = {},
    onOpenAppUpdate: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {}
) {
    val viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModelFactory(container))
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val isConnectorsConnected by viewModel.isConnectorsConnected.collectAsStateWithLifecycle()
    val isUpdateAvailable by viewModel.isUpdateAvailable.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAppearanceSheet by remember { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme() || themeMode == ThemeMode.DARK
    val screenBg = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF6F7F9)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFFFFFF)
    val cardBorderColor = if (isDark) Color(0xFF2A2F37) else Color(0xFFE5E7EB)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = screenBg,
                scrolledContainerColor = screenBg
            ),
            scrollBehavior = scrollBehavior
        )

        // Subtle divider on scroll
        if (scrollBehavior.state.contentOffset < -5f) {
            HorizontalDivider(
                color = cardBorderColor.copy(alpha = 0.6f),
                thickness = 0.8.dp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // =================================================================
            // Group 1: AI & Tools
            // =================================================================
            SectionLabel(text = "AI & Tools")

            SettingsGroupCard(
                backgroundColor = cardBg,
                borderColor = cardBorderColor
            ) {
                // 1. AI Providers
                SettingsItemRow(
                    icon = Icons.Outlined.Hub,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "AI Providers",
                    onClick = onOpenProviders
                )

                RowDivider(borderColor = cardBorderColor)

                // 2. Local AI
                SettingsItemRow(
                    icon = Icons.Outlined.Memory,
                    iconTint = Color(0xFF10B981),
                    title = "Local AI",
                    onClick = onOpenLocalAi
                )

                RowDivider(borderColor = cardBorderColor)

                // 3. Connectors
                SettingsItemRow(
                    icon = Icons.Outlined.Extension,
                    iconTint = Color(0xFF3B82F6),
                    title = "Connectors",
                    trailing = {
                        if (isConnectorsConnected) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "Connected",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onOpenConnectedApps
                )

                RowDivider(borderColor = cardBorderColor)

                // 4. Tasks
                SettingsItemRow(
                    icon = Icons.Outlined.Schedule,
                    iconTint = Color(0xFFE11D48),
                    title = "Tasks",
                    onClick = onOpenTasks
                )
            }

            Spacer(Modifier.height(12.dp))

            // =================================================================
            // Group 2: App Preferences
            // =================================================================
            SectionLabel(text = "App Preferences")

            SettingsGroupCard(
                backgroundColor = cardBg,
                borderColor = cardBorderColor
            ) {
                // 1. Appearance
                SettingsItemRow(
                    icon = Icons.Outlined.Palette,
                    iconTint = Color(0xFF8B5CF6),
                    title = "Appearance",
                    trailing = {
                        Text(
                            text = when (themeMode) {
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                                ThemeMode.SYSTEM -> "System"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = { showAppearanceSheet = true }
                )

                RowDivider(borderColor = cardBorderColor)

                // 2. Voice & Speech
                SettingsItemRow(
                    icon = Icons.Outlined.Mic,
                    iconTint = Color(0xFF06B6D4),
                    title = "Voice & Speech",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onOpenVoiceSettings
                )

                RowDivider(borderColor = cardBorderColor)

                // 3. Storage Access
                val hasStorageAccess = remember(themeMode) { DeviceStorage.hasAccess(context) }
                SettingsItemRow(
                    icon = Icons.Outlined.Folder,
                    iconTint = Color(0xFF0EA5E9),
                    title = "Storage Access",
                    trailing = {
                        Text(
                            text = if (hasStorageAccess) "Granted" else "Action needed",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp
                            ),
                            color = if (hasStorageAccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        val intent = DeviceStorage.accessIntent(context)
                            ?: android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                        runCatching { context.startActivity(intent) }
                    }
                )

                RowDivider(borderColor = cardBorderColor)

                // 3. Debug Console
                SettingsItemRow(
                    icon = Icons.Outlined.Terminal,
                    iconTint = Color(0xFFF59E0B),
                    title = "Debug Console",
                    onClick = onOpenDebugConsole
                )

                RowDivider(borderColor = cardBorderColor)

                // 4. Software Update
                SettingsItemRow(
                    icon = Icons.Outlined.SystemUpdate,
                    iconTint = Color(0xFF6366F1),
                    title = "Check for Updates",
                    trailing = {
                        if (isUpdateAvailable) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF6366F1).copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "Update",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = Color(0xFF6366F1),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "v${com.neuron.ai.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onOpenAppUpdate
                )
            }

            // =================================================================
            // Footer
            // =================================================================
            Spacer(Modifier.height(44.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAppUpdate)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandMark(modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Neuron-AI",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Version ${com.neuron.ai.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }

    // Appearance Modal Bottom Sheet
    if (showAppearanceSheet) {
        AppearanceBottomSheet(
            currentTheme = themeMode,
            onSelectTheme = { selectedMode ->
                viewModel.setThemeMode(selectedMode)
                showAppearanceSheet = false
            },
            onDismiss = { showAppearanceSheet = false },
            isDark = isDark,
            cardBorderColor = cardBorderColor
        )
    }
}

/**
 * Small muted section label (12sp–13sp) with clean uppercase styling.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 16.dp)
    )
}

/**
 * Pure white (or dark surface) grouped card container with 16dp rounded corners and subtle border.
 */
@Composable
private fun SettingsGroupCard(
    backgroundColor: Color,
    borderColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            content()
        }
    }
}

/**
 * Uniform 56dp–60dp settings row with leading tinted icon container, title, and trailing action.
 */
@Composable
private fun SettingsItemRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    trailing: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    },
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading icon inside subtle rounded tinted container
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        // Concise title (no multi-line subtitles)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // Trailing status badge or chevron
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            trailing()
        }
    }
}

@Composable
private fun RowDivider(borderColor: Color) {
    HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp, end = 16.dp),
        thickness = 0.6.dp,
        color = borderColor.copy(alpha = 0.5f)
    )
}

/**
 * Clean Modal Bottom Sheet for appearance selection (Light / Dark / System).
 * Displays 3 horizontal selectable icon cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceBottomSheet(
    currentTheme: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean,
    cardBorderColor: Color
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = if (isDark) MaterialTheme.colorScheme.surface else Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        CircleShape
                    )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Choose how Neuron-AI looks on your device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            // 3 Horizontal Selectable Icon Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeCard(
                    title = "Light",
                    icon = Icons.Outlined.LightMode,
                    selected = currentTheme == ThemeMode.LIGHT,
                    onClick = { onSelectTheme(ThemeMode.LIGHT) },
                    cardBorderColor = cardBorderColor,
                    modifier = Modifier.weight(1f)
                )

                ThemeCard(
                    title = "Dark",
                    icon = Icons.Outlined.DarkMode,
                    selected = currentTheme == ThemeMode.DARK,
                    onClick = { onSelectTheme(ThemeMode.DARK) },
                    cardBorderColor = cardBorderColor,
                    modifier = Modifier.weight(1f)
                )

                ThemeCard(
                    title = "System",
                    icon = Icons.Outlined.BrightnessAuto,
                    selected = currentTheme == ThemeMode.SYSTEM,
                    onClick = { onSelectTheme(ThemeMode.SYSTEM) },
                    cardBorderColor = cardBorderColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Individual selectable theme card inside the Appearance Bottom Sheet.
 */
@Composable
private fun ThemeCard(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    cardBorderColor: Color,
    modifier: Modifier = Modifier
) {
    val brandPurple = MaterialTheme.colorScheme.primary
    val borderColor = if (selected) brandPurple else cardBorderColor
    val borderWidth = if (selected) 2.dp else 1.dp
    val cardBackground = if (selected) {
        brandPurple.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Surface(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = cardBackground,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) brandPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = if (selected) brandPurple else MaterialTheme.colorScheme.onSurface
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Selected",
                        tint = brandPurple,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}
