package com.neuron.ai.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuron.ai.ui.components.entrancePop
import com.neuron.ai.ui.components.pressScale
import com.neuron.ai.ui.theme.Spacing

/**
 * Redesigned composer "+" panel: a single, grouped control surface for the
 * conversation. Built from two sections only — attach and tools — plus
 * workspace, so every option is visible in one scroll instead of being
 * hidden behind nested actions. Browser access defaults ON and says so.
 * Reply spacing is AI-controlled, so it is deliberately not a user control.
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
    val activeWorkspaceName = activeWorkspaceId?.let { id ->
        workspaces.firstOrNull { it.id == id }?.name
    }

    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            SheetHeader(onDismiss)

            Spacer(Modifier.height(Spacing.sm))

            // ---- Attach: three equal, high-contrast actions -----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                QuickAction(
                    icon = Icons.Outlined.PhotoCamera,
                    title = "Camera",
                    hint = "Take a photo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .entrancePop(delayMs = 0),
                    onClick = {
                        onDismiss()
                        onCamera()
                    }
                )
                QuickAction(
                    icon = Icons.Outlined.Photo,
                    title = "Photos",
                    hint = "From gallery",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .weight(1f)
                        .entrancePop(delayMs = 40),
                    onClick = {
                        onDismiss()
                        onPhotos()
                    }
                )
                QuickAction(
                    icon = Icons.Outlined.Description,
                    title = "Files",
                    hint = "Any document",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .weight(1f)
                        .entrancePop(delayMs = 80),
                    onClick = {
                        onDismiss()
                        onFiles()
                    }
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionLabel("Tools & access")
            ToolToggleRow(
                icon = Icons.Outlined.Public,
                title = "Browser",
                subtitle = if (browserEnabled) {
                    "On — AI can open and read web pages in this chat"
                } else {
                    "Off — browsing tools are unavailable"
                },
                tint = MaterialTheme.colorScheme.primary,
                enabled = browserEnabled,
                onToggle = { onToggleBrowser(!browserEnabled) },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    .entrancePop(delayMs = 120)
            )
            ToolToggleRow(
                icon = Icons.Outlined.Terminal,
                title = "Terminal",
                subtitle = if (terminalEnabled) {
                    "On — AI can run commands in the attached workspace"
                } else {
                    "Off — command tools are unavailable"
                },
                tint = MaterialTheme.colorScheme.tertiary,
                enabled = terminalEnabled,
                onToggle = { onToggleTerminal(!terminalEnabled) },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    .entrancePop(delayMs = 150)
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionLabel("Workspace")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = activeWorkspaceName ?: "No project attached",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.xs)
                )
                if (activeWorkspaceId != null) {
                    Text(
                        text = "Detach",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDetachWorkspace() }
                            .padding(horizontal = Spacing.xs, vertical = 2.dp)
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                workspaces.take(3).forEach { ws ->
                    WorkspaceChip(
                        name = ws.name,
                        selected = ws.id == activeWorkspaceId,
                        onClick = {
                            if (ws.id == activeWorkspaceId) onDetachWorkspace()
                            else onAttachWorkspace(ws.id)
                        }
                    )
                }
                WorkspaceChip(
                    name = "New",
                    selected = false,
                    leadingIcon = Icons.Outlined.Add,
                    onClick = onCreateWorkspace
                )
            }

            // A quiet confirmation line instead of another button: every
            // control above is a live, saved preference.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.md,
                    bottom = Spacing.xl
                )
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "Changes are saved automatically",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                        Color.Transparent
                    )
                )
            )
    ) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                ) {
                    Icon(
                        Icons.Outlined.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.sm)
                ) {
                    Text(
                        text = "Chat controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Attach, tools and how answers read",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onDismiss() }
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    title: String,
    hint: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        color = tint.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.25f)),
        modifier = modifier
            .height(88.dp)
            .pressScale(interaction, pressedScale = 0.95f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    )
}

@Composable
private fun ToolToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (enabled) tint.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(200),
        label = "toolRowBackground"
    )
    val badgeAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(180),
        label = "toolRowBadge"
    )
    Surface(
        onClick = onToggle,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) tint.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f))
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    if (badgeAlpha > 0.5f) {
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = "ON",
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(tint.copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The whole row is the tap target; the switch only mirrors state.
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

/** Selectable workspace chip; the active chip re-taps to detach. */
@Composable
private fun WorkspaceChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        modifier = Modifier.pressScale(interaction, pressedScale = 0.94f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(13.dp)
                        .padding(end = 2.dp)
                )
            }
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(13.dp)
                        .padding(end = 2.dp)
                )
            }
            Text(
                name,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
