package com.neuron.ai.ui.drawer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.BrandMark
import com.neuron.ai.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * Hamburger sidebar: new chat, full chat history, settings — the pattern users
 * know from ChatGPT/Gemini. Data comes straight from the conversation repository.
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

            Text(
                text = "Chats",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(conversations, key = { it.id }) { conversation ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
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
                        selected = false,
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = MaterialTheme.colorScheme.background
                        ),
                        onClick = { onOpenChat(conversation.id) },
                        modifier = Modifier.padding(horizontal = Spacing.md)
                    )
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
}
