package com.neuron.ai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing

/**
 * Bottom sheet behind the composer's "+" icon: Camera, Photos and Files in
 * medium-sized boxes, plus capability toggles — Terminal access and the
 * conversation's attached Workspace (Milestone 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    terminalEnabled: Boolean,
    onToggleTerminal: (Boolean) -> Unit,
    workspaces: List<com.neuron.ai.core.workspace.Workspace>,
    activeWorkspaceId: String?,
    onAttachWorkspace: (String) -> Unit,
    onDetachWorkspace: () -> Unit,
    onCreateWorkspace: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Tighter corners than the M3 default — less bubbly, more premium.
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        dragHandle = null
    ) {
        // Custom slim drag handle (M3 default replaced for tighter corners).
        androidx.compose.foundation.layout.Box(
            Modifier
                .padding(top = 10.dp)
                .width(36.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(2.dp)
                )
                .align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "Add to chat",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg)
        )
        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally)
        ) {
            SheetOption(
                icon = Icons.Outlined.PhotoCamera,
                label = "Camera",
                onClick = {
                    onDismiss()
                    onCamera()
                }
            )
            SheetOption(
                icon = Icons.Outlined.Photo,
                label = "Photos",
                onClick = {
                    onDismiss()
                    onPhotos()
                }
            )
            SheetOption(
                icon = Icons.Outlined.Description,
                label = "Files",
                onClick = {
                    onDismiss()
                    onFiles()
                }
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        // ---- Terminal capability toggle (per conversation, default OFF) ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text("Terminal access", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Let AI run commands in this chat's terminal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = terminalEnabled,
                onCheckedChange = onToggleTerminal
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // ---- Workspace picker -------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text("Workspace", style = MaterialTheme.typography.bodyMedium)
                Text(
                    activeWorkspaceId?.let { id -> workspaces.find { it.id == id }?.name }
                        ?: "None attached",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (activeWorkspaceId != null) {
                androidx.compose.material3.TextButton(onClick = onDetachWorkspace) {
                    Text("Detach")
                }
            }
            androidx.compose.material3.TextButton(onClick = onCreateWorkspace) {
                Text("+ New")
            }
        }
        if (workspaces.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
            ) {
                workspaces.take(3).forEach { ws ->
                    androidx.compose.material3.FilterChip(
                        selected = ws.id == activeWorkspaceId,
                        onClick = { onAttachWorkspace(ws.id) },
                        label = {
                            Text(ws.name, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp))
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun SheetOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(width = 96.dp, height = 84.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
