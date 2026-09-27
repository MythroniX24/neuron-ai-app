package com.neuron.ai.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuron.ai.ui.components.entrancePop
import com.neuron.ai.ui.components.pressScale
import com.neuron.ai.ui.theme.Spacing

/**
 * Bottom sheet behind the composer's "+" — v2 redesign.
 *
 * Design language: a compact drag handle + title row; media actions as a
 * vivid horizontal TILE GRID (taller touch targets, tinted icon medallions,
 * press-scale feedback); capabilities as unified single-tap STATUS CARDS
 * (whole card toggles — no fiddly little switch); workspace as horizontal
 * selectable chips with inline "New" — one row, no side buttons. Sections
 * stagger in with entrancePop so the panel feels alive on open.
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 320f))
        ) {
            // ---- Compact header: handle + title + close -------------------
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
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
                            .width(44.dp)
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Add to chat",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Media, tools and workspace for this conversation",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Explicit close affordance — the scrim taps back too.
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

            // ---- Media tiles -----------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                MediaTile(
                    icon = Icons.Outlined.PhotoCamera,
                    label = "Camera",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .entrancePop(delayMs = 0),
                    onClick = {
                        onDismiss()
                        onCamera()
                    }
                )
                MediaTile(
                    icon = Icons.Outlined.Photo,
                    label = "Photos",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .weight(1f)
                        .entrancePop(delayMs = 40),
                    onClick = {
                        onDismiss()
                        onPhotos()
                    }
                )
                MediaTile(
                    icon = Icons.Outlined.Description,
                    label = "Files",
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

            // ---- Capability status cards (single-tap toggle) ---------------
            SectionHeader("Capabilities")
            CapabilityCard(
                icon = Icons.Outlined.Terminal,
                title = "Terminal",
                subtitle = "AI runs commands in this chat",
                tint = MaterialTheme.colorScheme.primary,
                enabled = terminalEnabled,
                onToggle = { onToggleTerminal(!terminalEnabled) },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    .entrancePop(delayMs = 120)
            )
            CapabilityCard(
                icon = Icons.Outlined.Public,
                title = "Browser",
                subtitle = "AI opens and reads web pages",
                tint = MaterialTheme.colorScheme.tertiary,
                enabled = browserEnabled,
                onToggle = { onToggleBrowser(!browserEnabled) },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    .entrancePop(delayMs = 160)
            )

            Spacer(Modifier.height(Spacing.md))

            // ---- Workspace chips -------------------------------------------
            SectionHeader("Workspace")
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
                    text = activeWorkspaceId?.let { id -> workspaces.find { it.id == id }?.name }
                        ?: "No project attached",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.xs)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                workspaces.take(4).forEach { ws ->
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
            if (activeWorkspaceId != null) {
                Text(
                    text = "Tap the active chip again to detach",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                )
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Vivid media tile: tinted medallion icon over a labeled column. */
@Composable
private fun MediaTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .height(96.dp)
            .pressScale(interaction, pressedScale = 0.94f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.10f))
                        )
                    ),
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
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    )
}

/**
 * Capability card: the WHOLE card is the toggle — a leading medallion, title
 * + subtitle, and an animated status badge on the right. No separate switch
 * to aim for; one big tap target with clear on/off color language.
 */
@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val badgeColor by animateColorAsState(
        targetValue = if (enabled) tint else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(220),
        label = "capBadge"
    )
    val badgeIcon by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "capBadgeIcon"
    )
    Surface(
        onClick = onToggle,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) tint.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) tint.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = if (enabled) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
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
            // Animated ON/OFF status badge.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 52.dp, height = 26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(badgeColor)
            ) {
                Text(
                    text = if (badgeIcon > 0.5f) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    // onPrimary adapts per theme: white on the deep indigo
                    // (light), dark ink on the lifted lavender (dark).
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
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
