package com.neuron.ai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.neuron.ai.ui.components.pressScale
import com.neuron.ai.ui.theme.Spacing

/**
 * Bottom sheet behind the composer's "+": fully custom panel — gradient
 * header, large tappable action cards, and grouped capability rows with
 * custom pill toggles. No default Material bottom-sheet look.
 */
@Composable
fun AttachmentSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    terminalEnabled: Boolean,
    onToggleTerminal: (Boolean) -> Unit,
    browserEnabled: Boolean,
    onToggleBrowser: (Boolean) -> Unit,
    workspaces: List<com.neuron.ai.core.workspace.Workspace>,
    activeWorkspaceId: String?,
    onAttachWorkspace: (String) -> Unit,
    onDetachWorkspace: () -> Unit,
    onCreateWorkspace: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 320f))
        ) {
            // Gradient brand header with a custom drag handle.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Text(
                        text = "Add to chat",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md)
                    )
                    Spacer(Modifier.height(Spacing.md))
                }
            }

            // ---- Big action cards -------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                ActionCard(
                    icon = Icons.Outlined.PhotoCamera,
                    label = "Camera",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onCamera()
                    }
                )
                ActionCard(
                    icon = Icons.Outlined.Photo,
                    label = "Photos",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onPhotos()
                    }
                )
                ActionCard(
                    icon = Icons.Outlined.Description,
                    label = "Files",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onFiles()
                    }
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            // ---- Capability rows --------------------------------------------
            SectionLabel("Capabilities")
            CapabilityRow(
                icon = Icons.Outlined.Terminal,
                title = "Terminal access",
                subtitle = "Let AI run commands in this chat",
                iconTint = MaterialTheme.colorScheme.primary,
                checked = terminalEnabled,
                onChecked = onToggleTerminal
            )
            CapabilityRow(
                icon = Icons.Outlined.Public,
                title = "Browser access",
                subtitle = "Let AI open and read web pages",
                iconTint = MaterialTheme.colorScheme.tertiary,
                checked = browserEnabled,
                onChecked = onToggleBrowser
            )

            Spacer(Modifier.height(Spacing.md))

            // ---- Workspace ----------------------------------------------------
            SectionLabel("Workspace")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.md)
                ) {
                    Text(
                        activeWorkspaceId?.let { id -> workspaces.find { it.id == id }?.name }
                            ?: "None attached",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (activeWorkspaceId != null) "Tap a project below to switch"
                        else "Attach a project for file + terminal tools",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (activeWorkspaceId != null) {
                    CustomPillButton(
                        text = "Detach",
                        outlined = true,
                        onClick = onDetachWorkspace
                    )
                }
                CustomPillButton(text = "+ New", onClick = onCreateWorkspace)
            }
            if (workspaces.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                ) {
                    workspaces.take(3).forEach { ws ->
                        val selected = ws.id == activeWorkspaceId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            ),
                            onClick = { onAttachWorkspace(ws.id) }
                        ) {
                            Text(
                                ws.name,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.md, vertical = Spacing.xs
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Large gradient-accented action card with press feedback. */
@Composable
private fun ActionCard(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = modifier
            .height(92.dp)
            .pressScale(interaction, pressedScale = 0.95f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    )
}

/** Capability row with icon chip + custom pill toggle (no M3 Switch). */
@Composable
private fun CapabilityRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: androidx.compose.ui.graphics.Color,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { onChecked(!checked) },
        interactionSource = interaction,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md)
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Custom pill toggle — track + thumb drawn by hand.
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onChecked(!checked) },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .padding(start = if (checked) 22.dp else 3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (checked) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.surface
                        )
                )
            }
        }
    }
}

/** Small hand-drawn pill button used for Detach / + New. */
@Composable
private fun CustomPillButton(
    text: String,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (outlined) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.primary,
        border = if (outlined) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline
        ) else null,
        modifier = Modifier.padding(start = Spacing.xs)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (outlined) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
    }
}
