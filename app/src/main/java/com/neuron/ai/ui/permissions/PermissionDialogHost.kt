package com.neuron.ai.ui.permissions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.DecisionScope
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.permissions.PermissionRequest
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
 * at a time. DESTRUCTIVE requests offer no "remember" options — they must be
 * confirmed every single time.
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

        AlertDialog(
            onDismissRequest = { /* stay until decided */ },
            title = {
                Text(if (destructive) "Dangerous action" else "Permission needed")
            },
            text = {
                Column {
                    Text(
                        text = "${request.requestedBy} wants to use " +
                            "${PermissionUiMapper.capabilityLabel(request.capability)}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (request.reason.isNotBlank()) {
                        Text(
                            text = request.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (destructive) {
                        Text(
                            text = "This operation cannot be undone. It will always ask for confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            scope.launch {
                                permissionManager.decide(request.id, true, DecisionScope.ONCE)
                            }
                        }
                    ) { Text(if (destructive) "Confirm once" else "Allow once") }
                    if (!destructive) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    permissionManager.decide(request.id, true, DecisionScope.SESSION)
                                }
                            }
                        ) { Text("Allow for session") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    permissionManager.decide(request.id, true, DecisionScope.WORKSPACE)
                                }
                            }
                        ) { Text("Always allow") }
                    }
                }
            },
            dismissButton = {
                Column {
                    TextButton(
                        onClick = {
                            scope.launch {
                                permissionManager.decide(request.id, false, DecisionScope.ONCE)
                            }
                        }
                    ) { Text("Deny once") }
                    if (!destructive) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    permissionManager.decide(request.id, false, DecisionScope.ALWAYS)
                                }
                            }
                        ) {
                            Text("Always deny", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        )
    }
}
