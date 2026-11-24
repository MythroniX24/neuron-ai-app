package com.neuron.ai.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import com.neuron.ai.ui.components.rememberHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.neuron.ai.core.settings.ReplySpacingProfile
import com.neuron.ai.core.settings.ResponseSpacing
import com.neuron.ai.ui.components.FullScreenImage
import com.neuron.ai.ui.components.LocalImageOpener
import com.neuron.ai.ui.components.NeuronAsyncImage
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing

/**
 * Renders parsed markdown blocks with the Neuron design system. Code blocks
 * get language label, copy button, syntax highlighting and horizontal scroll;
 * display math renders via JLatexMath with graceful fallback.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    /** AI-selected spacing, independently for prose and lists. */
    spacing: ReplySpacingProfile = ReplySpacingProfile(),
    /** Active word to highlight for real-time karaoke read-aloud. */
    highlightWord: String? = null,
    /** Exact occurrence index of highlightWord to highlight (0-indexed). */
    highlightOccurrence: Int = 0
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    var targetBlockIndex = -1
    var occurrenceInTargetBlock = -1

    if (!highlightWord.isNullOrBlank()) {
        var globalMatchesSeen = 0
        val pattern = Regex("""(?i)\b${Regex.escape(highlightWord.trim())}\b""")
        for (i in blocks.indices) {
            val block = blocks[i]
            val blockText = when (block) {
                is MdBlock.Paragraph -> block.text
                is MdBlock.Heading -> block.text
                is MdBlock.Quote -> block.text
                is MdBlock.ListBlock -> block.items.joinToString(" ") { it.text }
                else -> ""
            }
            val countInBlock = pattern.findAll(blockText).count()
            if (countInBlock > 0 && highlightOccurrence < globalMatchesSeen + countInBlock) {
                targetBlockIndex = i
                occurrenceInTargetBlock = highlightOccurrence - globalMatchesSeen
                break
            }
            globalMatchesSeen += countInBlock
        }
    }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val isTarget = index == targetBlockIndex
            BlockView(
                block = block,
                spacing = spacing,
                highlightWord = if (isTarget) highlightWord else null,
                highlightOccurrence = if (isTarget) occurrenceInTargetBlock else 0
            )
            if (index != blocks.lastIndex) {
                // The paragraph-gap choice is its own setting: it never rides
                // along with the line-spacing choice.
                Spacer(Modifier.height(Spacing.sm * spacing.gap.blockGapScale))
            }
        }
    }
}

/**
 * Scales a design-system style's line height (never its font size) and pins
 * [LineHeightStyle] so the extra leading is actually visible. Without it the
 * first/last line's leading is trimmed and "line spacing" appears to do
 * nothing — which is exactly how it read before.
 */
private fun TextStyle.withResponseSpacing(spacing: ResponseSpacing): TextStyle {
    val base = lineHeight
    val scaled = if (base != TextUnit.Unspecified) {
        (base.value * spacing.lineHeightScale).sp
    } else {
        (fontSize.value * DEFAULT_LINE_HEIGHT_RATIO * spacing.lineHeightScale).sp
    }
    return copy(
        lineHeight = scaled,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
}

private const val DEFAULT_LINE_HEIGHT_RATIO = 1.5f

@Composable
private fun BlockView(
    block: MdBlock,
    spacing: ReplySpacingProfile,
    highlightWord: String? = null,
    highlightOccurrence: Int = 0
) {
    val paragraph = spacing.paragraph
    val list = spacing.list
    when (block) {
        is MdBlock.Heading -> Text(
            text = InlineMarkdown.toAnnotatedString(
                block.text,
                highlightWord = highlightWord,
                highlightOccurrence = highlightOccurrence
            ),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall.withResponseSpacing(paragraph)
                2 -> MaterialTheme.typography.titleLarge.withResponseSpacing(paragraph)
                else -> MaterialTheme.typography.titleMedium.withResponseSpacing(paragraph)
            },
            fontWeight = FontWeight.SemiBold
        )

        is MdBlock.Paragraph -> Text(
            text = InlineMarkdown.toAnnotatedString(
                block.text,
                highlightWord = highlightWord,
                highlightOccurrence = highlightOccurrence
            ),
            style = MaterialTheme.typography.bodyLarge.withResponseSpacing(paragraph)
        )

        // A picture from the web, user attachment, or AI generated image:
        // Ultra-fast sub-millisecond cached rendering, real aspect ratio,
        // tappable to open full-screen (cross to close, download at top right).
        is MdBlock.Image -> {
            val opener = LocalImageOpener.current
            NeuronAsyncImage(
                model = block.url,
                contentDescription = block.alt.ifBlank { "Image" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md)),
                onClick = { opener(FullScreenImage(block.url, block.alt)) }
            )
        }

        // Agent-authored UI (A2UI): native cards, file lists, forms.
        is MdBlock.A2ui -> com.neuron.ai.ui.a2ui.A2uiBlockView(block.json)

        // Native rich recommendations (movies, tools, products, books, etc.)
        is MdBlock.RichItemsBlock -> {
            val set = remember(block.json) {
                com.neuron.ai.core.conversation.RichItemsParser.parse(block.json)
            }
            if (set != null) {
                com.neuron.ai.ui.chat.RichRecommendationSection(
                    recommendations = listOf(set),
                    responseSpacing = spacing.paragraph,
                    imageUrls = com.neuron.ai.ui.components.LocalComparisonImages.current,
                    missingQueries = com.neuron.ai.ui.components.LocalMissingImages.current,
                    onImagesNeeded = com.neuron.ai.ui.components.LocalOnImagesNeeded.current,
                    modifier = Modifier.padding(vertical = Spacing.xs)
                )
            }
        }

        // Native interactive question card
        is MdBlock.QuestionBlock -> {
            val payload = remember(block.json) {
                com.neuron.ai.core.conversation.QuestionCardParser.parse(block.json)
            }
            if (payload != null) {
                val onSubmit = com.neuron.ai.ui.chat.LocalQuestionSubmit.current
                com.neuron.ai.ui.chat.InteractiveQuestionCard(
                    payload = payload,
                    onOptionSelected = { onSubmit(it) },
                    onDismiss = {},
                    modifier = Modifier.padding(vertical = Spacing.xs)
                )
            }
        }

        // Native interactive math & geometry / tree diagram canvas
        is MdBlock.DiagramBlock -> {
            com.neuron.ai.ui.chat.diagram.InteractiveDiagramCard(
                typeHint = block.type,
                rawContent = block.content,
                modifier = Modifier.padding(vertical = Spacing.xs)
            )
        }

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.LatexBlock -> LatexBlockView(block.latex)

        is MdBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .padding(end = Spacing.sm)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
            )
            Text(
                text = InlineMarkdown.toAnnotatedString(
                    block.text,
                    highlightWord = highlightWord,
                    highlightOccurrence = highlightOccurrence
                ),
                style = MaterialTheme.typography.bodyLarge.withResponseSpacing(paragraph),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is MdBlock.HorizontalRule -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        ) {}

        is MdBlock.ListBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs * list.blockGapScale)
        ) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        // The model's own numbers are kept: a list that gets split
                        // into several blocks (nested bullets do that) then keeps
                        // reading 1, 2, 3 instead of 1, 1, 1.
                        text = if (block.ordered) "${item.number ?: (index + 1)}." else "•",
                        style = MaterialTheme.typography.bodyLarge.withResponseSpacing(list),
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                    Text(
                        text = InlineMarkdown.toAnnotatedString(
                            item.text,
                            highlightWord = highlightWord,
                            highlightOccurrence = highlightOccurrence
                        ),
                        style = MaterialTheme.typography.bodyLarge.withResponseSpacing(list)
                    )
                }
            }
        }

        is MdBlock.Table -> TableView(block)
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHapticFeedback()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    val isDark = com.neuron.ai.ui.theme.LocalAppDarkMode.current
    val codeBackground = if (isDark) Color(0xFF16191E) else Color(0xFFF1F3F5)
    val headerBackground = if (isDark) Color(0xFF1E222A) else Color(0xFFE5E7EB)
    val borderColor = if (isDark) Color(0xFF2A2F37) else Color(0xFFD1D5DB)

    val highlighted = remember(block.code, block.language, isDark) {
        SyntaxHighlighter.highlight(
            code = block.code,
            language = block.language,
            dark = isDark
        )
    }

    val lines = remember(block.code) { block.code.lines() }
    val showLineNumbers = lines.size > 1

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(codeBackground)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(Radius.md))
    ) {
        // Sleek Header Bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(headerBackground)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Language Badge / Pill
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isDark) Color(0xFF2B313C) else Color(0xFFD6DAE1)
            ) {
                Text(
                    text = (block.language?.takeIf { it.isNotBlank() } ?: "CODE").uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Copy Code Button with Animated Status & Haptic
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(block.code))
                        copied = true
                        haptic.confirmation()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (copied) "Copied!" else "Copy",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Code Body with Line Numbers
        SelectionContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs)
            ) {
                if (showLineNumbers) {
                    Column(
                        modifier = Modifier.padding(start = Spacing.sm, end = Spacing.xs),
                        horizontalAlignment = Alignment.End
                    ) {
                        lines.indices.forEach { lineIdx ->
                            Text(
                                text = "${lineIdx + 1}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            )
                        }
                    }
                    // Vertical subtle divider between line numbers and code
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(IntrinsicSize.Min)
                            .background(borderColor.copy(alpha = 0.5f))
                    )
                }

                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.sm)
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
    }
}

@Composable
private fun TableView(table: MdBlock.Table) {
    val numCols = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (numCols == 0) return

    val headers = List(numCols) { i -> table.headers.getOrElse(i) { "" } }
    val rows = table.rows.map { row -> List(numCols) { i -> row.getOrElse(i) { "" } } }

    val colWidths = remember(headers, rows) {
        List(numCols) { c ->
            val headerLen = headers[c].length
            val maxRowLen = rows.maxOfOrNull { it[c].length } ?: 0
            val maxLen = maxOf(headerLen, maxRowLen)
            when {
                maxLen <= 6 -> 84.dp
                maxLen <= 14 -> 120.dp
                maxLen <= 24 -> 160.dp
                maxLen <= 40 -> 210.dp
                else -> 260.dp
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                headers.forEachIndexed { idx, header ->
                    if (idx > 0) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(colWidths[idx])
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = InlineMarkdown.toAnnotatedString(header),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                thickness = 1.dp
            )

            // Data Rows
            rows.forEachIndexed { rIdx, row ->
                val bg = if (rIdx % 2 == 1) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                } else {
                    Color.Transparent
                }
                Row(
                    modifier = Modifier
                        .background(bg)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEachIndexed { cIdx, cell ->
                        if (cIdx > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(colWidths[cIdx])
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = InlineMarkdown.toAnnotatedString(cell),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (rIdx < rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun LatexBlockView(latex: String) {
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LatexText(
            latex = latex,
            textStyle = MaterialTheme.typography.bodyLarge,
            fallback = { Text(latex, style = MaterialTheme.typography.bodyLarge) }
        )
    }
}

@Composable
private fun LatexText(
    latex: String,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    fallback: @Composable () -> Unit
) {
    val drawable = remember(latex) {
        runCatching {
            ru.noties.jlatexmath.JLatexMathDrawable.builder(latex)
                .textSize(44f)
                .padding(8)
                .build()
        }.getOrNull()
    }

    if (drawable != null) {
        androidx.compose.foundation.Image(
            painter = rememberDrawablePainter(drawable),
            contentDescription = "Math formula",
            modifier = Modifier.padding(Spacing.xs)
        )
    } else {
        fallback()
    }
}
