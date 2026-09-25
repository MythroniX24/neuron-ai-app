package com.neuron.ai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shared draggable panel container for the terminal panel and the "+" sheet:
 *
 *  - drag the top strip UP → panel grows toward FULL SCREEN
 *  - drag DOWN past the close threshold → dismisses (caller animates it out)
 *  - release anywhere → springs to the nearest anchor (rest or full)
 *
 * The panel follows the finger 1:1 while dragging; settle animation only
 * runs on release. Content gets all height below the drag strip.
 */
@Composable
fun DraggablePanelContainer(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    restFraction: Float = 0.55f,
    fullFraction: Float = 0.94f,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val scope = rememberCoroutineScope()

        // Settled height anchor; while dragging the panel tracks the finger.
        val settled = remember { Animatable(restFraction) }
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(restFraction) }
        val shown = if (dragging) dragValue else settled.value

        Column(
            Modifier
                .fillMaxWidth()
                .height(maxHeight * shown)
        ) {
            // Full-width drag strip — an easy, generous grab target.
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(restFraction, fullFraction) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragValue = settled.value
                            },
                            onDragEnd = {
                                dragging = false
                                val f = dragValue
                                val target = when {
                                    f < 0.34f -> null // dismiss
                                    f > 0.72f -> fullFraction
                                    else -> restFraction
                                }
                                scope.launch {
                                    settled.snapTo(dragValue)
                                    if (target == null) {
                                        onDismiss()
                                    } else {
                                        settled.animateTo(
                                            target,
                                            spring(dampingRatio = 0.85f, stiffness = 320f)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                scope.launch {
                                    settled.snapTo(dragValue)
                                    settled.animateTo(
                                        restFraction,
                                        spring(dampingRatio = 0.85f, stiffness = 320f)
                                    )
                                }
                            }
                        ) { _, dragAmount ->
                            dragValue = (dragValue - dragAmount / maxHeightPx)
                                .coerceIn(0.20f, 1f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .padding(vertical = 10.dp)
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
