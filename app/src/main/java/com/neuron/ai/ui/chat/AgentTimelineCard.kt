package com.neuron.ai.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuron.ai.core.conversation.AgentStepRecord
import com.neuron.ai.ui.theme.Spacing
import kotlin.math.max

/**
 * Live agent activity timeline (Claude-style).
 *
 * Slim vertical rail: one line per step, a distinct icon per step type, muted
 * secondary text, a soft pulse ONLY on the currently running step, collapsed
 * by default. Tapping a finished step expands its full detail — resolved
 * lazily by [fullResultResolver] from the conversation's persisted TOOL
 * messages, so no output is duplicated into the timeline itself.
 */
@Composable
fun AgentTimelineCard(
    steps: List<AgentStepRecord>,
    modifier: Modifier = Modifier,
    fullResultResolver: (String) -> String? = { null }
) {
    if (steps.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            steps.forEachIndexed { index, step ->
                TimelineRow(
                    step = step,
                    first = index == 0,
                    last = index == steps.lastIndex,
                    fullResultResolver = fullResultResolver
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    step: AgentStepRecord,
    first: Boolean,
    last: Boolean,
    fullResultResolver: (String) -> String?
) {
    var expanded by remember(step.stepId) { mutableStateOf(false) }
    val running = step.status == AgentStepRecord.STATUS_RUNNING
    val failed = step.status == AgentStepRecord.STATUS_FAILED

    val iconTint by animateColorAsState(
        targetValue = when {
            failed -> MaterialTheme.colorScheme.error
            running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "timelineTint"
    )

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !running) { expanded = !expanded }
            .padding(vertical = 2.dp)
    ) {
        // Leading rail column: connector segments above/below the icon.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(if (first) 6.dp else 12.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(26.dp)
            ) {
                if (running) {
                    val transition = rememberInfiniteTransition(label = "timelinePulse")
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        infiniteRepeatable(
                            animation = tween(700, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "timelinePulseAlpha"
                    )
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * alpha),
                                CircleShape
                            )
                    )
                }
                Icon(
                    imageVector = typeIcon(step.type),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(15.dp)
                )
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(if (last) 6.dp else 12.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        Spacer(Modifier.width(Spacing.sm))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val duration = durationLabel(step)
                if (duration != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                val full = fullResultResolver(step.stepId)
                if (!full.isNullOrBlank()) {
                    Text(
                        text = full,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 14,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }
        }

        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = when (step.status) {
                AgentStepRecord.STATUS_DONE -> "✓"
                AgentStepRecord.STATUS_FAILED -> "✗"
                else -> "⟳"
            },
            style = MaterialTheme.typography.labelMedium,
            color = iconTint
        )
    }
}

private fun typeIcon(type: String): ImageVector = when (type) {
    AgentStepRecord.TYPE_THINKING -> Icons.Outlined.Psychology
    AgentStepRecord.TYPE_INTERMEDIATE -> Icons.Outlined.Chat
    AgentStepRecord.TYPE_COMMAND -> Icons.Outlined.Terminal
    AgentStepRecord.TYPE_WEB_SEARCH -> Icons.Outlined.Search
    AgentStepRecord.TYPE_FILE_READ -> Icons.Outlined.Description
    AgentStepRecord.TYPE_FILE_EDIT -> Icons.Outlined.Edit
    AgentStepRecord.TYPE_BUILD_TEST -> Icons.Outlined.Build
    else -> Icons.Outlined.Bolt
}

/** "1.2s" style duration for finished steps; null while running. */
private fun durationLabel(step: AgentStepRecord): String? {
    val end = step.finishedAtEpochMs ?: return null
    val seconds = max(0, end - step.startedAtEpochMs) / 1000.0
    return if (seconds >= 0.05) String.format("%.1fs", seconds) else null
}
