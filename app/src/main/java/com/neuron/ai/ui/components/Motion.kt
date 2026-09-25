package com.neuron.ai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Neuron-AI motion primitives — ONE shared language for the whole app:
 * quick springs, subtle movement, nothing bouncy-cartoonish.
 *
 * Everything runs through `graphicsLayer`/draw phases so animating never
 * recomposes the content behind it.
 */

/**
 * Press feedback: scales to [pressedScale] while the pointer is down and
 * springs back on release. Share the SAME [interactionSource] you pass to
 * the clickable/Surface so it tracks real presses.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumHigh
        ),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Convenience overload that owns its interaction source. */
fun Modifier.pressScale(pressedScale: Float = 0.96f): Modifier = composed {
    pressScale(remember { MutableInteractionSource() }, pressedScale)
}

/**
 * One-shot entrance: pops from 85% scale/transparent to full, with an
 * optional stagger [delayMs]. [enabled] = false renders statically — used
 * to avoid replaying the pop for recycled (scrolled-back) list items.
 */
fun Modifier.entrancePop(delayMs: Int = 0, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed Modifier
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        anim.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 380f))
    }
    graphicsLayer {
        val v = anim.value
        scaleX = 0.85f + 0.15f * v
        scaleY = 0.85f + 0.15f * v
        alpha = v
    }
}

/**
 * Directional message entrance. [fromEnd] = user bubble slides in from the
 * right; otherwise the assistant reply rises slightly and fades in.
 * [enabled] = false renders statically (recycled items never re-animate).
 */
fun Modifier.messageEntrance(fromEnd: Boolean, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed Modifier
    val anim = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        anim.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        val v = anim.value
        alpha = v
        with(density) {
            if (fromEnd) translationX = (1f - v) * 24.dp.toPx()
            else translationY = (1f - v) * 10.dp.toPx()
        }
    }
}

/** Draw-time shimmer sweep used by skeletons and the streaming placeholder. */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    drawWithCache {
        val start = -size.width + (2f * size.width + 80f) * progress
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight.copy(alpha = 0.7f), base),
            start = Offset(start, 0f),
            end = Offset(start + size.width * 0.6f, size.height)
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * "Neuron pulse" — a slow breathing halo behind the brand mark. This is the
 * signature: the app visibly breathes while the agent is thinking.
 */
fun Modifier.neuronPulse(enabled: Boolean): Modifier = composed {
    if (!enabled) return@composed Modifier
    val transition = rememberInfiniteTransition(label = "neuronPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulsePhase"
    )
    val glow = MaterialTheme.colorScheme.primary
    drawWithCache {
        val radius = size.minDimension * (0.55f + 0.25f * phase)
        onDrawWithContent {
            drawCircle(glow.copy(alpha = 0.10f + 0.08f * phase), radius)
            drawContent()
        }
    }
}

/** Skeleton bar for loading placeholders. */
@Composable
fun SkeletonBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .shimmer()
    )
}
