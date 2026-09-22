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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.permissions.PermissionManager
import com.neuron.ai.core.permissions.PermissionRequest

/** UI-facing labels for capabilities. */
object PermissionUiMapper {
    fun capabilityLabel(capability: Capability): String = when (capability) {
        Capability.FILESYSTEM_READ -> "Read files"
        Capability.FILESYSTEM_WRITE -> "Write files"
        Capability.NETWORK -> "Network access"
        Capability.BROWSER -> "Browser control"
        Capability.TERMINAL -> "Terminal access"
        Capability.NOTIFICATIONS -> "Notifications"
    }
}

/**
 * Observes [PermissionManager.pendingRequests] and renders one allow/deny
 * dialog at a time. Lives above the NavHost so requests surface anywhere.
 */
@Composable
fun PermissionDialogHost(
    permissionManager: PermissionManager,
    content: @Composable () -> Unit
) {
    val pending by permissionManager.pendingRequests.collectAsStateWithLifecycle(initialValue = emptyList())

    content()

    pending.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = { /* stay until decided */ },
            title = { Text("Permission needed") },
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
                }
            },
            confirmButton = {
                TextButton(onClick = { permissionManager.grant(request.id) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionManager.deny(request.id) }) {
                    Text("Deny")
                }
            }
        )
    }
}
