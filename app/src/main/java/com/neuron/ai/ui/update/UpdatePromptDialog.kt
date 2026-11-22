package com.neuron.ai.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuron.ai.core.update.AppUpdateInfo
import com.neuron.ai.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gate that quietly checks for an update on launch and displays a one-time
 * popup dialog when a new release is available and has not yet been prompted.
 */
@Composable
fun UpdatePromptGate(
    container: AppContainer,
    onOpenUpdate: () -> Unit
) {
    var promptInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            container.appUpdateManager.checkForUpdate(notifyIfAvailable = true)
        }
        val info = result.getOrNull()
        if (info != null && info.isUpdateAvailable) {
            if (container.appUpdateManager.shouldPromptOnLaunch(info.releaseTag)) {
                promptInfo = info
            }
        }
    }

    promptInfo?.let { info ->
        UpdatePromptDialog(
            info = info,
            onDismiss = {
                container.appUpdateManager.dismissPrompt(info.releaseTag)
                promptInfo = null
            },
            onUpdateNow = {
                container.appUpdateManager.dismissPrompt(info.releaseTag)
                promptInfo = null
                onOpenUpdate()
            }
        )
    }
}

@Composable
fun UpdatePromptDialog(
    info: AppUpdateInfo,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFF6366F1).copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = Color(0xFF6366F1),
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "New Update Available",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = info.releaseTag,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF6366F1),
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Version badge comparison
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "v${info.currentVersion}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }

                        Text(
                            text = "→",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Latest",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF6366F1)
                            )
                            Text(
                                text = "v${info.latestVersion}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6366F1)
                                )
                            )
                        }
                    }
                }

                // Summary or notes preview
                if (info.releaseNotes.isNotBlank()) {
                    Text(
                        text = info.releaseNotes.lines().take(6).joinToString("\n").trim(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "A new version of Neuron-AI is ready with performance improvements, bug fixes, and new features.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateNow,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Update Now")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Later")
            }
        }
    )
}
