package com.neuron.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Animated live audio waveform visualizer bars responding in real time to microphone audio input levels.
 */
@Composable
fun AudioWaveformVisualizer(
    audioLevel: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 5,
    activeColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_idle")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveform_phase"
    )

    val barWeights = listOf(0.45f, 0.75f, 1.0f, 0.8f, 0.5f)

    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val weight = barWeights.getOrElse(i) { 0.6f }
            val oscillation = ((sin(idlePhase + i * 1.15f) + 1f) / 2f)
            val baseMinHeight = 4.dp
            // Calculate dynamic height based on audio level plus gentle breathing wave
            val levelFactor = (audioLevel * weight + oscillation * 0.12f).coerceIn(0.08f, 1f)
            val targetHeight = baseMinHeight + (18.dp * levelFactor)

            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "bar_height_$i"
            )

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(animatedHeight)
                    .background(
                        color = activeColor.copy(alpha = (0.55f + audioLevel * 0.45f).coerceIn(0.55f, 1f)),
                        shape = RoundedCornerShape(50)
                    )
            )
        }
    }
}
