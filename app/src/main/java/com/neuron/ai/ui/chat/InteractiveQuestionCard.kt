package com.neuron.ai.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuron.ai.core.conversation.InteractiveQuestion
import com.neuron.ai.core.conversation.QuestionCardPayload
import com.neuron.ai.ui.markdown.InlineMarkdown
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing

val LocalQuestionSubmit = compositionLocalOf<(String) -> Unit> { {} }

/**
 * Interactive Question Card matching Claude's tappable multiple-choice UI:
 * - "1 of N" paging indicator with dismiss 'X'
 * - Question title in clean bold typography
 * - Divider-separated numbered tappable option rows (1, 2, 3...)
 * - Bottom custom answer input ("Type your own answer...") with circular submit button
 * - Supports single and multi-step question sequences
 */
@Composable
fun InteractiveQuestionCard(
    payload: QuestionCardPayload,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (payload.questions.isEmpty()) return

    var currentIndex by remember(payload.id) { mutableIntStateOf(0) }
    val answers = remember(payload.id) { mutableStateMapOf<Int, String>() }
    var customText by remember(payload.id, currentIndex) { mutableStateOf("") }

    val total = payload.questions.size
    val currentQuestion: InteractiveQuestion = payload.questions.getOrElse(currentIndex) { payload.questions.first() }

    fun handleAnswer(answer: String) {
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return
        answers[currentIndex] = trimmed

        if (currentIndex < total - 1) {
            currentIndex++
        } else {
            val finalResult = if (total == 1) {
                trimmed
            } else {
                payload.questions.mapIndexed { idx, q ->
                    val ans = answers[idx] ?: (if (idx == currentIndex) trimmed else "")
                    "${idx + 1}. ${q.question}: $ans"
                }.joinToString("\n")
            }
            onOptionSelected(finalResult)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg)),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            // Header: "1 of N" on the left, Close 'X' on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentIndex > 0) {
                        IconButton(
                            onClick = { currentIndex-- },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Previous question",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.xs))
                    }
                    Text(
                        text = if (total > 1) "${currentIndex + 1} of $total" else (payload.title ?: "Question"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss question",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Animated question & options transition
            AnimatedContent(
                targetState = currentIndex to currentQuestion,
                transitionSpec = {
                    if (targetState.first > initialState.first) {
                        (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> width / 3 } + fadeOut())
                    }
                },
                label = "questionTransition"
            ) { (_, q) ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Question text
                    Text(
                        text = InlineMarkdown.toAnnotatedString(q.question),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            lineHeight = 23.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = Spacing.sm)
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    // Options list
                    q.options.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                thickness = 0.8.dp
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.sm))
                                .clickable { handleAnswer(option) }
                                .padding(vertical = Spacing.md, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Circular number badge (1, 2, 3...)
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.width(Spacing.md))

                            Text(
                                text = InlineMarkdown.toAnnotatedString(option),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Divider before custom text input if options exist
                    if (q.options.isNotEmpty() && q.allowCustom) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            thickness = 0.8.dp,
                            modifier = Modifier.padding(bottom = Spacing.xs)
                        )
                    }

                    // Bottom custom answer input: "Type your own answer..."
                    if (q.allowCustom) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = Spacing.xs),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (customText.isEmpty()) {
                                    Text(
                                        text = "Type your own answer...",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                BasicTextField(
                                    value = customText,
                                    onValueChange = { customText = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = { handleAnswer(customText) }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Circular submit button (terracotta / brand accent)
                            val hasInput = customText.isNotBlank()
                            val buttonColor = if (hasInput) {
                                Color(0xFFD97706) // Claude-style warm terracotta amber
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val iconColor = if (hasInput) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(color = buttonColor, shape = CircleShape)
                                    .clip(CircleShape)
                                    .clickable(enabled = hasInput) { handleAnswer(customText) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ArrowUpward,
                                    contentDescription = "Submit answer",
                                    tint = iconColor,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
