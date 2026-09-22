package com.neuron.ai.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
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
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        blocks.forEach { block -> BlockView(block) }
    }
}

@Composable
private fun BlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = InlineMarkdown.toAnnotatedString(stripInline(block.text)),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold
        )

        is MdBlock.Paragraph -> Text(
            text = InlineMarkdown.toAnnotatedString(block.text),
            style = MaterialTheme.typography.bodyLarge
        )

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.LatexBlock -> LatexBlockView(block.latex)

        is MdBlock.Quote -> Row {
            Column(
                Modifier
                    .padding(end = Spacing.sm)
                    .width(3.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.primary)
            ) {}
            Text(
                text = InlineMarkdown.toAnnotatedString(block.text),
                style = MaterialTheme.typography.bodyLarge,
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

        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = if (block.ordered) "${index + 1}." else "•",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                    Text(
                        text = InlineMarkdown.toAnnotatedString(item),
                        style = MaterialTheme.typography.bodyLarge
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
    val codeColor = MaterialTheme.colorScheme.onSurface
    val highlighted = remember(block.code, block.language) {
        SyntaxHighlighter.highlight(
            code = block.code,
            language = block.language
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(Radius.md)
            )
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(Radius.md)
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SelectionContainer {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md)
            )
        }
        // Bottom breathing room.
        androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun TableView(table: MdBlock.Table) {
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(Radius.sm)
            )
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            table.headers.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(Spacing.sm)
                        .widthIn(min = 72.dp)
                )
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(Spacing.sm)
                            .widthIn(min = 72.dp)
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
                .textSize(44)
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
