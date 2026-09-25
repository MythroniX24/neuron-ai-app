package com.neuron.ai.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.neuron.ai.ui.components.entrancePop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.core.conversation.Conversation
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.BrandMark
import com.neuron.ai.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Hamburger sidebar: new chat, searchable chat history with long-press
 * actions (pin / rename / delete), settings. Pinned chats float to the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val conversations by container.conversationRepository.conversations
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val repo = container.conversationRepository

    // Instant title filter — chat titles derive from their first message, so
    // this doubles as content search for finding older chats.
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        conversations
    } else {
        conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    // Long-press action target + dialog states.
    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // ---- Brand header ------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
            ) {
                BrandMark()
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = "Neuron-AI",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                label = { Text("New chat") },
                selected = false,
                onClick = onNewChat,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search chats") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
            )

            Text(
                text = "Chats",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            text = "No chats match \"$query\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                        )
                    }
                }
                items(filtered, key = { it.id }) { conversation ->
                    // Staggered entrance — first few items cascade in when
                    // the drawer opens; later items appear without delay.
                    val index = filtered.indexOf(conversation)
                    Box(
                        Modifier.entrancePop(
                            delayMs = if (index < 6) index * 40 else 0
                        )
                    ) {
                        DrawerChatItem(
                            conversation = conversation,
                            onClick = { onOpenChat(conversation.id) },
                            onLongPress = { actionTarget = conversation }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text("Settings") },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }
    }

    // ---- Long-press action sheet (custom panel, matches the attach sheet) ----
    actionTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Text(
                text = target.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
            DrawerActionRow(
                icon = Icons.Outlined.PushPin,
                label = if (target.pinned) "Unpin" else "Pin to top",
                tint = MaterialTheme.colorScheme.primary
            ) {
                actionTarget = null
                scope.launch { repo.setConversationPinned(target.id, !target.pinned) }
            }
            DrawerActionRow(
                icon = Icons.Outlined.DriveFileRenameOutline,
                label = "Rename",
                tint = MaterialTheme.colorScheme.secondary
            ) {
                actionTarget = null
                renameTarget = target
            }
            DrawerActionRow(
                icon = Icons.Outlined.Delete,
                label = "Delete",
                tint = MaterialTheme.colorScheme.error,
                labelColor = MaterialTheme.colorScheme.error
            ) {
                actionTarget = null
                deleteTarget = target
            }
            Spacer(Modifier.size(Spacing.xl))
        }
    }

    // ---- Rename dialog ---------------------------------------------------------
    renameTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        renameTarget = null
                        val newTitle = draft.trim()
                        scope.launch { repo.renameConversation(target.id, newTitle) }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ---- Delete confirmation ---------------------------------------------------
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = {
                Text("\"" + target.title + "\" and all its messages will be removed permanently.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch { repo.deleteConversation(target.id) }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerChatItem(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                tint = if (conversation.pinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        // NavigationDrawerItem has no supportingContent slot;
        // the date rides inside the two-line label instead.
        label = {
            Column {
                Text(
                    conversation.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT)
                        .format(Date(conversation.updatedAtEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        badge = {
            if (conversation.pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        selected = false,
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = MaterialTheme.colorScheme.background
        ),
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    )
}

/** Custom action row for the long-press sheet — icon chip + label, no M3 ListItem. */
@Composable
private fun DrawerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            modifier = Modifier.padding(start = Spacing.md)
        )
    }
}
