package com.neuron.ai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuron.ai.R
import com.neuron.ai.ui.components.ambientNeuralGlow
import com.neuron.ai.ui.components.entrancePop
import com.neuron.ai.ui.components.neuronPulse
import com.neuron.ai.ui.components.pressScale
import com.neuron.ai.ui.components.rememberAnimatedGradientBrush
import com.neuron.ai.ui.theme.Spacing

/** One tappable starter on the empty chat screen. */
internal data class ChatSuggestion(
    val label: String,
    val prompt: String,
    val icon: ImageVector,
    val accentColor: Color = Color(0xFF8B5CF6)
)

private val DefaultSuggestions = listOf(
    ChatSuggestion(
        label = "Explain a topic",
        prompt = "Explain how neural networks learn, with a simple analogy.",
        icon = Icons.Outlined.Lightbulb,
        accentColor = Color(0xFFF59E0B) // Amber
    ),
    ChatSuggestion(
        label = "Search the web",
        prompt = "Search the web for the latest breakthroughs in AI agents.",
        icon = Icons.Outlined.Search,
        accentColor = Color(0xFF06B6D4) // Cyan
    ),
    ChatSuggestion(
        label = "Code & debug",
        prompt = "Help me write and debug clean, idiomatic code.",
        icon = Icons.Outlined.Code,
        accentColor = Color(0xFF8B5CF6) // Purple
    )
)

/**
 * First-open hero for a brand-new chat:
 * - Living Neural Core Emblem with multi-layer breathing sweep aura
 * - Futuristic greeting pill badge
 * - Shimmering animated gradient headline ("How can I help you today?")
 * - Exactly 3 minimal, uncluttered suggestion capsules
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyChatHero(
    onSuggestion: (ChatSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxl, bottom = Spacing.xl)
            .entrancePop()
    ) {
        // Living Neural Core Emblem with ambient breathing aura
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(108.dp)
                .ambientNeuralGlow()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(
                    1.5.dp,
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF8B5CF6).copy(alpha = 0.7f),
                            Color(0xFF06B6D4).copy(alpha = 0.5f),
                            Color(0xFF3B82F6).copy(alpha = 0.7f)
                        )
                    )
                ),
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.brand_logo),
                        contentDescription = "Neuron AI Brand Logo",
                        modifier = Modifier
                            .size(46.dp)
                            .neuronPulse(enabled = true)
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // Futuristic greeting pill badge
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
            modifier = Modifier.entrancePop(delayMs = 60)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "✦",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = greeting().uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Shimmering animated gradient headline
        val headlineBrush = rememberAnimatedGradientBrush(
            colors = listOf(
                Color(0xFF8B5CF6), // Royal Purple
                Color(0xFF06B6D4), // Electric Cyan
                Color(0xFF3B82F6), // Vibrant Blue
                Color(0xFFEC4899), // Neon Pink
                Color(0xFF8B5CF6)  // Loop
            ),
            durationMs = 4500
        )

        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                brush = headlineBrush
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = Spacing.md)
                .entrancePop(delayMs = 120)
        )

        Spacer(Modifier.height(Spacing.xs))

        Text(
            text = "Ask questions, write code, or explore ideas with your on-device AI.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .entrancePop(delayMs = 180)
        )

        Spacer(Modifier.height(Spacing.xl))

        // Exactly 3 clean, uncluttered suggestion capsules
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
        ) {
            DefaultSuggestions.forEachIndexed { index, suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    delayMs = 240 + index * 70,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSuggestion(suggestion)
                    }
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, suggestion.accentColor.copy(alpha = 0.35f)),
        tonalElevation = 1.dp,
        modifier = Modifier
            .entrancePop(delayMs)
            .pressScale(interaction, pressedScale = 0.94f)
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
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(suggestion.accentColor.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = suggestion.icon,
                    contentDescription = null,
                    tint = suggestion.accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = suggestion.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
