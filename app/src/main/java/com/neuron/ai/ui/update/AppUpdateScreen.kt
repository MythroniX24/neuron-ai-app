package com.neuron.ai.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.BuildConfig
import com.neuron.ai.core.update.AppUpdateCheckState
import com.neuron.ai.core.update.AppUpdateInfo
import com.neuron.ai.core.update.DownloadState
import com.neuron.ai.core.update.InstallResult
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.markdown.MarkdownText
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: AppUpdateViewModel = viewModel(
        factory = AppUpdateViewModelFactory(container)
    )

    val checkState by viewModel.checkState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val cachedApk by viewModel.cachedApkFile.collectAsStateWithLifecycle()
    val installResult by viewModel.installResult.collectAsStateWithLifecycle()

    val isDark = isSystemInDarkTheme()
    val screenBg = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF8F9FA)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFFFFFF)
    val cardBorderColor = if (isDark) Color(0xFF2A2F37) else Color(0xFFE5E7EB)

    val isChecking = checkState is AppUpdateCheckState.Checking

    // Rotate refresh icon during active checking
    val refreshRotation by animateFloatAsState(
        targetValue = if (isChecking) 360f else 0f,
        label = "refreshRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "App Update",
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
            actions = {
                IconButton(
                    onClick = { viewModel.checkForUpdate(force = true) },
                    enabled = !isChecking
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Check for updates",
                        modifier = Modifier.rotate(refreshRotation),
                        tint = if (isChecking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = screenBg,
                scrolledContainerColor = screenBg
            )
        )

        HorizontalDivider(
            color = cardBorderColor.copy(alpha = 0.6f),
            thickness = 0.8.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Status Card
            StatusHeroCard(
                checkState = checkState,
                cachedApk = cachedApk,
                cardBg = cardBg,
                cardBorder = cardBorderColor
            )

            // Release Notes Card (if update available)
            if (checkState is AppUpdateCheckState.Available) {
                val info = (checkState as AppUpdateCheckState.Available).updateInfo
                ReleaseNotesCard(
                    info = info,
                    cardBg = cardBg,
                    cardBorder = cardBorderColor
                )
            } else if (checkState is AppUpdateCheckState.UpToDate) {
                UpToDateFeaturesCard(
                    cardBg = cardBg,
                    cardBorder = cardBorderColor
                )
            }

            // Actions Area
            ActionCard(
                checkState = checkState,
                downloadState = downloadState,
                cachedApk = cachedApk,
                cardBg = cardBg,
                cardBorder = cardBorderColor,
                onCheckAgain = { viewModel.checkForUpdate(force = true) },
                onStartDownload = { info -> viewModel.startDownload(info) },
                onCancelDownload = { viewModel.cancelDownload() },
                onInstall = { file -> viewModel.installApk(file) }
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // Unknown sources permission dialog
    if (installResult is InstallResult.NeedsUnknownSourcesPermission) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissInstallResult() },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Permission Required",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "Android requires permission to install updates directly from Neuron-AI. Please enable 'Allow from this source' in Settings, then tap Install again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissInstallResult()
                    viewModel.requestUnknownSourcesPermission()
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissInstallResult() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Install error dialog
    if (installResult is InstallResult.Error) {
        val err = (installResult as InstallResult.Error).message
        AlertDialog(
            onDismissRequest = { viewModel.dismissInstallResult() },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Installation Failed") },
            text = { Text(err) },
            confirmButton = {
                Button(onClick = { viewModel.dismissInstallResult() }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun StatusHeroCard(
    checkState: AppUpdateCheckState,
    cachedApk: File?,
    cardBg: Color,
    cardBorder: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (checkState) {
                is AppUpdateCheckState.Checking -> {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Checking for Updates...",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Connecting to GitHub Releases",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is AppUpdateCheckState.Available -> {
                    val info = checkState.updateInfo
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color(0xFF6366F1).copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Update Available",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = info.releaseTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF6366F1)
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    // Version pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VersionPill(label = "Installed", version = "v${info.currentVersion}", isHighlight = false)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        VersionPill(label = "Latest", version = "v${info.latestVersion}", isHighlight = true)
                    }

                    if (info.apkSizeBytes > 0 || info.publishedAt.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (info.apkSizeBytes > 0) {
                                Text(
                                    text = "Size: ${formatFileSize(info.apkSizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (info.publishedAt.isNotBlank()) {
                                Text(
                                    text = "Released: ${formatDate(info.publishedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                is AppUpdateCheckState.UpToDate -> {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color(0xFF10B981).copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Neuron-AI is Up to Date",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Version ${checkState.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is AppUpdateCheckState.Error -> {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Check Failed",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = checkState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is AppUpdateCheckState.Idle -> {
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionPill(label: String, version: String, isHighlight: Boolean) {
    val pillBg = if (isHighlight) Color(0xFF6366F1).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val pillTextColor = if (isHighlight) Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = pillBg
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = pillTextColor.copy(alpha = 0.8f)
            )
            Text(
                text = version,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = pillTextColor
            )
        }
    }
}

@Composable
private fun ReleaseNotesCard(
    info: AppUpdateInfo,
    cardBg: Color,
    cardBorder: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "What's New",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF6366F1).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = info.releaseTag,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF6366F1),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (info.releaseNotes.isNotBlank()) {
                MarkdownText(
                    markdown = info.releaseNotes,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "No changelog notes provided for this release.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UpToDateFeaturesCard(
    cardBg: Color,
    cardBorder: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "Everything is Current",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "You are enjoying the latest features, security patches, and AI agent enhancements available for Neuron-AI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionCard(
    checkState: AppUpdateCheckState,
    downloadState: DownloadState,
    cachedApk: File?,
    cardBg: Color,
    cardBorder: Color,
    onCheckAgain: () -> Unit,
    onStartDownload: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (File) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                // If checking
                checkState is AppUpdateCheckState.Checking -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Checking GitHub releases repository...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // If error checking
                checkState is AppUpdateCheckState.Error -> {
                    Button(
                        onClick = onCheckAgain,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry Check")
                    }
                }

                // If up to date
                checkState is AppUpdateCheckState.UpToDate -> {
                    OutlinedButton(
                        onClick = onCheckAgain,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Check Again")
                    }
                }

                // If update available
                checkState is AppUpdateCheckState.Available -> {
                    val info = checkState.updateInfo

                    when (downloadState) {
                        is DownloadState.Downloading -> {
                            val prog = downloadState.progress
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading update...",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${prog.percent}%",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF6366F1)
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { prog.percent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${formatFileSize(prog.bytesDownloaded)} / ${formatFileSize(prog.totalBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    TextButton(onClick = onCancelDownload) {
                                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        is DownloadState.Downloaded -> {
                            val file = downloadState.apkFile
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Ready to install: ${file.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = { onInstall(file) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                                ) {
                                    Icon(imageVector = Icons.Outlined.SystemUpdate, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Install Update Now")
                                }
                            }
                        }

                        is DownloadState.Error -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = downloadState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Button(
                                    onClick = { onStartDownload(info) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(imageVector = Icons.Outlined.FileDownload, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Retry Download")
                                }
                            }
                        }

                        is DownloadState.Idle -> {
                            // If cached APK file already exists and matches size
                            if (cachedApk != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Update APK is already downloaded in cache.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { onInstall(cachedApk) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                                    ) {
                                        Icon(imageVector = Icons.Outlined.SystemUpdate, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Install Update Now")
                                    }
                                    FilledTonalButton(
                                        onClick = { onStartDownload(info) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(imageVector = Icons.Outlined.FileDownload, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Re-download APK")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { onStartDownload(info) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                                ) {
                                    Icon(imageVector = Icons.Outlined.CloudDownload, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    val sizeLabel = if (info.apkSizeBytes > 0) " (${formatFileSize(info.apkSizeBytes)})" else ""
                                    Text("Download Update$sizeLabel")
                                }
                            }
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onCheckAgain,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Check for Updates")
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}

private fun formatDate(isoString: String): String {
    return try {
        // e.g. "2026-11-21T10:58:02Z" -> "2026-11-21"
        if (isoString.length >= 10) {
            isoString.substring(0, 10)
        } else {
            isoString
        }
    } catch (_: Exception) {
        isoString
    }
}
