package com.neuron.ai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neuron.ai.R
import com.neuron.ai.ui.components.entrancePop
import com.neuron.ai.ui.components.neuronPulse
import com.neuron.ai.ui.components.pressScale
import com.neuron.ai.ui.theme.Spacing

/** One tappable starter on the empty chat screen. */
internal data class ChatSuggestion(
    val label: String,
    val prompt: String,
    val icon: ImageVector
)

private val DefaultSuggestions = listOf(
    ChatSuggestion(
        "Explain a topic",
        "Explain how neural networks learn, with a simple analogy.",
        Icons.Outlined.Lightbulb
    ),
    ChatSuggestion(
        "Debug an error",
        "I keep running into this error:\n\n(paste the error here)\n\n" +
            "Help me understand and fix it.",
        Icons.Outlined.BugReport
    ),
    ChatSuggestion(
        "Write something",
        "Help me write a short, friendly update to my team about our " +
            "progress this week.",
        Icons.Outlined.Edit
    ),
    ChatSuggestion(
        "Plan my day",
        "Help me plan a realistic schedule for today — I have 4 focused hours.",
        Icons.Outlined.EventNote
    )
)

/**
 * First-open hero for a brand-new chat: the breathing brand mark, the
 * greeting, and tappable starter prompts that PRE-FILL the composer (tap →
 * edit → send). Motion uses the shared primitives — entrancePop stagger,
 * pressScale feedback, and the signature neuronPulse halo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyChatHero(
    onSuggestion: (ChatSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.xl, bottom = Spacing.lg)
            .entrancePop()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(88.dp)
        ) {
            // The signature breathing halo — the app visibly "breathes".
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .neuronPulse(enabled = true)
            )
            Image(
                painter = painterResource(R.drawable.brand_logo),
                contentDescription = null,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = greeting(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "What can I help you with?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(Spacing.xl))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Spacing.sm)
        ) {
            DefaultSuggestions.forEachIndexed { index, suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    delayMs = index * 70,
                    onClick = { onSuggestion(suggestion) }
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: ChatSuggestion,
    delayMs: Int,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .entrancePop(delayMs)
            .pressScale(interaction, pressedScale = 0.95f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = suggestion.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = suggestion.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
