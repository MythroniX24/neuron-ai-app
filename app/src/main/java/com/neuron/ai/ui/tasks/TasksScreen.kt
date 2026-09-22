package com.neuron.ai.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neuron.ai.core.task.Task
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.components.EmptyState
import com.neuron.ai.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * Task manager UI: running tasks with a stop affordance, plus finished history.
 * States map directly onto [Task.Status].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val viewModel: TasksViewModel =
        viewModel(factory = TasksViewModelFactory(container))
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Tasks", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            actions = {
                if (tasks.any { it.status.isFinished }) {
                    TextButton(onClick = viewModel::clearFinished) {
                        Text("Clear finished")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        if (tasks.isEmpty()) {
            EmptyState(
                title = "No tasks yet",
                description = "Agent activity (like tool runs) appears here.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.xl)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = Spacing.lg, vertical = Spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(task = task, onStop = { viewModel.stop(task.id) })
                }
            }
        }
    }
}

private val Task.Status.isFinished: Boolean
    get() = this == Task.Status.DONE ||
        this == Task.Status.FAILED ||
        this == Task.Status.CANCELLED

@Composable
private fun TaskRow(task: Task, onStop: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (task.status == Task.Status.RUNNING) {
                    LinearProgressIndicator(
                        modifier = Modifier.size(width = 48.dp, height = 3.dp)
                    )
                }
                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(task.updatedAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = when (task.status) {
                    Task.Status.QUEUED, Task.Status.PAUSED -> Icons.Outlined.Schedule
                    Task.Status.RUNNING -> Icons.Outlined.Schedule
                    Task.Status.DONE -> Icons.Outlined.CheckCircle
                    Task.Status.FAILED -> Icons.Outlined.ErrorOutline
                    Task.Status.CANCELLED -> Icons.Outlined.Cancel
                },
                contentDescription = task.status.name,
                tint = when (task.status) {
                    Task.Status.DONE -> MaterialTheme.colorScheme.primary
                    Task.Status.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
        },
        trailingContent = {
            if (task.status == Task.Status.RUNNING || task.status == Task.Status.QUEUED) {
                TextButton(onClick = onStop) { Text("Stop") }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
