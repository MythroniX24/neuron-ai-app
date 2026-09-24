package com.neuron.ai.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.DecisionScope
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.permissions.PermissionRequest
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.launch

/** UI-facing labels for capabilities. */
object PermissionUiMapper {
    fun capabilityLabel(capability: Capability): String = when (capability) {
        Capability.FILESYSTEM_READ -> "Read files"
        Capability.FILESYSTEM_WRITE -> "Write files"
        Capability.FILESYSTEM_DELETE -> "Delete files"
        Capability.EXECUTE -> "Run commands"
        Capability.NETWORK -> "Network access"
        Capability.BROWSER -> "Browser control"
        Capability.TERMINAL -> "Terminal access"
        Capability.NOTIFICATIONS -> "Notifications"
    }
}

/**
 * Observes [PermissionManager.pendingRequests] and renders one decision dialog
 * at a time. Custom dialog surface with FULL-WIDTH STACKED buttons — the old
 * AlertDialog slot layout made the options overlap on small screens.
 *
 * DESTRUCTIVE requests offer no "remember" options — they must be confirmed
 * every single time.
 */
@Composable
fun PermissionDialogHost(
    permissionManager: PermissionManager,
    content: @Composable () -> Unit
) {
    val pending by permissionManager.pendingRequests.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    content()

    pending.firstOrNull()?.let { request ->
        val destructive = request.riskLevel == RiskLevel.DESTRUCTIVE ||
            request.capability == Capability.FILESYSTEM_DELETE

        Dialog(onDismissRequest = { /* stay until decided */ }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Spacing.lg)) {
                    Text(
                        text = if (destructive) "Dangerous action" else "Permission needed",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${request.requestedBy} wants to use " +
                            "${PermissionUiMapper.capabilityLabel(request.capability)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                    if (request.reason.isNotBlank()) {
                        Text(
                            text = request.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                    if (destructive) {
                        Text(
                            text = "This operation cannot be undone. It will always ask for confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Spacing.sm)
                        )
                    }

                    // Stacked, full-width actions — one per line, never overlapping.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = Spacing.md)
                    ) {
                        DialogAction(
                            label = if (destructive) "Confirm once" else "Allow once",
                            primary = true
                        ) {
                            scope.launch {
                                permissionManager.decide(request.id, true, DecisionScope.ONCE)
                            }
                        }
                        if (!destructive) {
                            DialogAction(label = "Allow for this session") {
                                scope.launch {
                                    permissionManager.decide(request.id, true, DecisionScope.SESSION)
                                }
                            }
                            DialogAction(label = "Always allow") {
                                scope.launch {
                                    permissionManager.decide(request.id, true, DecisionScope.WORKSPACE)
                                }
                            }
                            DialogAction(label = "Deny once") {
                                scope.launch {
                                    permissionManager.decide(request.id, false, DecisionScope.ONCE)
                                }
                            }
                            DialogAction(label = "Always deny", danger = true) {
                                scope.launch {
                                    permissionManager.decide(request.id, false, DecisionScope.ALWAYS)
                                }
                            }
                        } else {
                            DialogAction(label = "Cancel", danger = true) {
                                scope.launch {
                                    permissionManager.decide(request.id, false, DecisionScope.ONCE)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                danger -> MaterialTheme.colorScheme.error
                primary -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
